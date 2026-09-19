package io.github.oatelauser.thunder.core.internal.tracker;

import io.github.oatelauser.thunder.core.internal.wire.CompactPeer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP Tracker 客户端（BEP 15）：connect（64 位 connection_id，60s 缓存）→ announce。
 * 16 字节精简报文；事务 ID 校验；指数退避重传（2 次重试）。
 * 一个实例服务多个 tracker 主机：connection_id 按 host:port 缓存。
 *
 * <p>收发分离：懒启动单条虚拟线程接收循环阻塞守候共享 socket，按事务 ID 分发到
 * 各调用方的 future；发送方注册事务（tid→future）后发送并限时等待，超时换新 tid
 * 退避重传。并发 announce 无须在共享 socket 上排队（此前 synchronized exchange
 * 串行化，后到者最坏排队约 16s）。
 */
public final class UdpTrackerClient implements AutoCloseable {

    private static final long CONNECT_PROTOCOL_ID = 0x41727101980AL;
    /** BEP 15：connection_id 客户端侧最多缓存 1 分钟；tracker 侧容忍到 2 分钟。 */
    private static final long CONNECTION_TTL_MILLIS = 60_000;
    /**
     * 重传次数（首次 + 2 次重试）。退避基数 500ms：BEP 15 建议 15×2ⁿ 秒（上限 n=8），
     * 那是整点退避的保守值；库内 announce 挂在虚拟线程上、上层 tier 失败转移另有
     * 全局退避，故按毫秒级缩短，避免单 tracker 故障拖住整个发现周期。
     */
    private static final int MAX_ATTEMPTS = 3;
    /** 单笔事务等待应答的上限（原 socket soTimeout 的等价物）；超时换新 tid 重传。 */
    private static final long RESPONSE_TIMEOUT_MILLIS = 5_000;

    private record Connection(long id, long acquiredMillis) {
    }

    /** 在途事务：expectedAction（connect=0/announce=1）供接收循环校验应答。 */
    private record Pending(int transactionId, int expectedAction,
            CompletableFuture<ByteBuffer> future) {
    }

    private final DatagramSocket socket;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Pending> transactions = new ConcurrentHashMap<>();
    private final AtomicBoolean receiverStarted = new AtomicBoolean();
    private volatile boolean closed;

    public UdpTrackerClient() throws IOException {
        this.socket = new DatagramSocket();
    }

    /**
     * 支持 udp://host:port/announce 形态；其他 scheme 返回 false 交回 HTTP 客户端。
     */
    public static boolean supports(String url) {
        return url != null && url.startsWith("udp://");
    }

    public AnnounceResponse announce(String announceUrl, AnnounceRequest request) {
        InetSocketAddress address = parse(announceUrl);
        if (address == null) {
            throw new TrackerException("invalid udp tracker url: " + announceUrl);
        }
        try {
            long connectionId = connectionFor(address);
            try {
                return announceWith(address, connectionId, request);
            } catch (TrackerErrorResponse e) {
                return reconnectAndAnnounce(address, request);
            }
        } catch (IOException e) {
            throw new TrackerException("udp tracker I/O failure: " + announceUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException("udp tracker interrupted", e);
        }
    }

    /**
     * announce 收到 error（action=3）后的整体重试：清除该地址的 connection 缓存，
     * 重新 connect + announce，一轮内最多一次。理由：本仓库 tracker 服务端按自身
     * 时钟对 connection_id 做 60s TTL 校验，而客户端的 60s 从本地获取时刻起算——
     * 两端时钟漂移/调度延迟会让缓存的 id 先在服务端过期（恰好卡在边界），收到
     * stale 类 error 属预期路径而非故障；按 BEP 15 惯例丢弃缓存重连一次即可恢复。
     * 只重试一次是防止 tracker 对任何 id 都报错时陷入 connect↔error 循环。
     */
    private AnnounceResponse reconnectAndAnnounce(InetSocketAddress address,
            AnnounceRequest request) throws IOException, InterruptedException {
        connections.remove(connectionKey(address));
        return announceWith(address, connectionFor(address), request);
    }

    private long connectionFor(InetSocketAddress address) throws IOException, InterruptedException {
        String key = connectionKey(address);
        Connection cached = connections.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.acquiredMillis() < CONNECTION_TTL_MILLIS) {
            return cached.id();
        }
        ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putLong(CONNECT_PROTOCOL_ID)
                .putInt(0) // action: connect
                .putInt(0); // tid 占位：exchange 每次尝试覆写偏移 12
        ByteBuffer response = exchange(address, out, 0);
        long id = response.getLong(8);
        connections.put(key, new Connection(id, now));
        return id;
    }

    private static String connectionKey(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private AnnounceResponse announceWith(InetSocketAddress address, long connectionId,
            AnnounceRequest request)
            throws IOException, InterruptedException {
        // BEP 15 announce 请求（98 字节，偏移为规范值）：downloaded@56 → left@64 →
        // uploaded@72 → event@80 → ip@84 → key@88 → num_want@92 → port@96（2 字节大端）。
        // ip=0 由 tracker 自取源地址；key=0（无会话标识）。字段顺序/宽度错位会被真实
        // tracker 解读成 num_want=0 / port=0（曾把 left/uploaded 写反、port 写成 int
        // 且尾部 6 字节未写满——UdpTrackerClientTest 对逐字段偏移有断言）
        ByteBuffer out = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN)
                .putLong(connectionId)
                .putInt(1) // action: announce
                .putInt(0) // tid 占位：exchange 每次尝试覆写偏移 12
                .put(request.infoHash())
                .put(request.peerId())
                .putLong(request.downloaded())
                .putLong(request.left())
                .putLong(request.uploaded())
                .putInt(eventAction(request.event()))
                .putInt(0) // ip
                .putInt(0) // key
                .putInt(request.numwant())
                .putShort((short) request.port());
        ByteBuffer response = exchange(address, out, 1);
        // BEP 15 应答头：action/transaction_id 之外为 interval/leechers/seeders（注意与 HTTP
        // 的 complete/incomplete 顺序相反，leechers 在前），peer 紧凑表从偏移 20 起
        int interval = response.getInt(8);
        int leechers = response.getInt(12);
        int seeders = response.getInt(16);
        List<InetSocketAddress> peers = new ArrayList<>();
        for (int offset = 20; offset + 6 <= response.limit(); offset += 6) {
            peers.add(CompactPeer.decode6(response, offset));
        }
        return new AnnounceResponse(interval, seeders, leechers, peers, null);
    }

    /**
     * 发送并等待 tid 匹配的应答；指数退避重传（每次换新 tid，旧 future 取消注销）。
     *
     * <p>注册先于发送：若先发后注册，快应答可能在注册完成前到达而被接收循环当作
     * 无人认领丢弃。tracker 明确回 error（action=3）时不重传——重发同样报文只会
     * 得到同样应答——以 TrackerErrorResponse 直达上层（announce 据此重连一次）；
     * 截短/action 不符的畸形应答按本轮失败退避重试。
     */
    private ByteBuffer exchange(InetSocketAddress address, ByteBuffer wire, int expectedAction)
            throws IOException, InterruptedException {
        if (closed) {
            throw new IOException("client closed");
        }
        startReceiver();
        long backoff = 500;
        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (closed) {
                throw new IOException("client closed");
            }
            Pending pending = registerTransaction(expectedAction);
            wire.putInt(12, pending.transactionId()); // tid 在 connect/announce 里都固定偏移 12
            try {
                socket.send(new DatagramPacket(wire.array(), wire.array().length,
                        address.getAddress(), address.getPort()));
                return awaitResponse(pending);
            } catch (IOException e) {
                transactions.remove(pending.transactionId());
                if (closed) {
                    throw new IOException("client closed");
                }
                last = e;
            } catch (InterruptedException e) {
                pending.future().cancel(false);
                transactions.remove(pending.transactionId());
                throw e;
            }
            Thread.sleep(backoff);
            backoff *= 2;
        }
        throw last != null ? last : new IOException("udp exchange failed");
    }

    /**
     * 限时等待事务应答并转译异常完成：超时抛 SocketTimeoutException，close/接收循环
     * 退出/畸形应答抛 IOException（三者均可退避重传）；error 应答抛 TrackerErrorResponse
     * （RuntimeException）绕过重传直达上层。
     */
    private ByteBuffer awaitResponse(Pending pending) throws IOException, InterruptedException {
        try {
            return pending.future().get(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.future().cancel(false);
            throw new SocketTimeoutException("udp response timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TrackerErrorResponse error) {
                throw error;
            }
            throw cause instanceof IOException failure ? failure : new IOException(cause);
        }
    }

    /**
     * 注册在途事务：tid 用 ThreadLocalRandom 生成（只作请求/应答匹配，无加密强度
     * 要求），putIfAbsent 撞上同号在途事务则重生成，避免顶掉别人的 future。
     */
    private Pending registerTransaction(int expectedAction) {
        while (true) {
            int transactionId = ThreadLocalRandom.current().nextInt();
            Pending pending = new Pending(transactionId, expectedAction, new CompletableFuture<>());
            if (transactions.putIfAbsent(transactionId, pending) == null) {
                return pending;
            }
        }
    }

    /** 懒启动共享接收循环（首笔事务才需要线程；CAS 防并发调用重复启动）。 */
    private void startReceiver() {
        if (receiverStarted.compareAndSet(false, true)) {
            Thread.ofVirtual().name("udp-tracker-client-receiver").start(this::receiveLoop);
        }
    }

    /**
     * 接收循环：阻塞守候共享 socket，按 tid 分发。close() 关 socket 使阻塞的
     * receive 抛 SocketException 而退出；socket 关闭之外的 IOException 意味着再
     * 无人收应答（客户端不可用），同样失败全部在途事务并退出，防调用方悬挂。
     */
    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                packet.setLength(buffer.length); // 复用 packet：重置容量，否则截短为上次收到的字节数
                socket.receive(packet);
            } catch (IOException e) {
                if (!closed) {
                    terminate("udp receive loop failed: " + e);
                }
                return;
            }
            dispatch(packet);
        }
    }

    /**
     * 按 tid 分发应答：无人认领（迟到旧事务/杂音/前代 tid 重传残留）直接丢弃；
     * action=3 转 TrackerErrorResponse；action 与期待不符或长度不足最小值视为畸形，
     * 二者均以异常完成 future，由发送方决定重传或上抛。
     */
    private void dispatch(DatagramPacket packet) {
        if (packet.getLength() < 8) {
            return; // 连 action/tid 头都凑不齐：杂音
        }
        ByteBuffer response = ByteBuffer.wrap(
                        Arrays.copyOf(packet.getData(), packet.getLength()))
                .order(ByteOrder.BIG_ENDIAN);
        Pending pending = transactions.remove(response.getInt(4));
        if (pending == null) {
            return; // 迟到旧事务：无人认领，丢弃
        }
        int action = response.getInt(0);
        if (action == 3) {
            response.position(8);
            byte[] message = new byte[response.remaining()];
            response.get(message);
            pending.future().completeExceptionally(new TrackerErrorResponse("udp tracker error: "
                    + new String(message, StandardCharsets.UTF_8)));
            return;
        }
        if (action != pending.expectedAction()
                || response.remaining() < minResponseBytes(pending.expectedAction())) {
            // 损坏/恶意 tracker 的 tid 匹配截短包：解析期会越界读穿透
            //（调用方只捕获 TrackerException），按畸形失败交发送方退避重试
            pending.future().completeExceptionally(
                    new IOException("malformed udp tracker response, action=" + action));
            return;
        }
        pending.future().complete(response);
    }

    /**
     * tid 匹配应答的最小长度：connect 的 connection_id@8..16 需 16 字节，
     * announce 的 seeders@16..20 需 20 字节（peer 紧凑表按剩余量截尾，无下限）。
     */
    private static int minResponseBytes(int expectedAction) {
        return expectedAction == 0 ? 16 : 20;
    }

    private static int eventAction(TrackerEvent event) {
        return switch (event) {
            case STARTED -> 2;
            case COMPLETED -> 1;
            case STOPPED -> 3;
            case NONE -> 0;
        };
    }

    private static InetSocketAddress parse(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 6969;
            if (host == null) {
                return null;
            }
            InetAddress resolved = InetAddress.getByName(host); // 阻塞 DNS；调用方在虚拟线程上
            return new InetSocketAddress(resolved, port);
        } catch (RuntimeException | UnknownHostException e) {
            return null;
        }
    }

    /**
     * 关闭：关 socket（解除接收线程的阻塞 receive）并以异常完成全部在途事务，防
     * 调用方悬挂等待。幂等——重复 close、以及与接收线程故障退出路径并发调用均
     * 安全（completeExceptionally 对已完成 future 是 no-op）。极端竞态下（close 与
     * 发送方注册交错）新注册的事务至多多等一个响应超时即失败，不会悬挂。
     */
    @Override
    public void close() {
        terminate("udp tracker client closed");
    }

    private void terminate(String reason) {
        closed = true;
        socket.close();
        IOException failure = new IOException(reason);
        for (Pending pending : transactions.values()) {
            pending.future().completeExceptionally(failure);
        }
        transactions.clear();
    }

    /**
     * tracker 的 error 应答（action=3）。announce 阶段收到一律视为 connection_id
     * 已在服务端过期（见 {@link #reconnectAndAnnounce} 的时钟边界说明），不解析
     * 错误文本猜测语义——真实 tracker 的措辞并无规范约束。
     */
    private static final class TrackerErrorResponse extends TrackerException {

        TrackerErrorResponse(String message) {
            super(message);
        }
    }
}
