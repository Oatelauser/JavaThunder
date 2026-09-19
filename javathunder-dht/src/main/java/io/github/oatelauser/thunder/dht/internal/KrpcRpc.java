package io.github.oatelauser.thunder.dht.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.jspecify.annotations.Nullable;

/**
 * KRPC 传输层（BEP 5）：单 UDP socket + 接收线程 + 事务表。只负责报文收发与
 * 事务配对（roundTrip 阻塞等待 / sendQuery 发后不管），不含任何查找或路由语义——
 * 响应者观察（路由表登记）经构造器注入的 observer 回调交给上层；他人发来的
 * 查询（y=q）经 {@link #onQuery} 注入的 handler 交给服务侧（KrpcServer）应答。
 */
final class KrpcRpc implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KrpcRpc.class);
    private static final int QUERY_TIMEOUT_MILLIS = 2000;
    /** 接收缓冲：KRPC 实践报文 <1.5KB（大 nodes 列表居多数百字节），4KB 留足余量。 */
    private static final int RECEIVE_BUFFER_BYTES = 4096;
    /**
     * 事务 ID 宽度：主流 DHT 实现约定 2 字节；并发下有生日碰撞概率，碰撞方在
     * {@link #register} 内换新 ID 重试（仍撞则退回覆盖语义，由 2s 超时兜底）。
     */
    private static final int TRANSACTION_ID_BYTES = 2;
    /** tid 碰撞的重生成上限：连续撞说明事务表近乎占满，再试无益，退回覆盖语义。 */
    private static final int TRANSACTION_ID_RETRIES = 3;

    private final DatagramSocket socket;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 待处理事务：事务ID → future。
     */
    private final ConcurrentHashMap<String, CompletableFuture<KrpcMessage.Parsed>> transactions =
            new ConcurrentHashMap<>();
    /**
     * 响应者观察（路由表登记等上层语义）：参数为来源地址与解析后的报文。
     */
    private final BiConsumer<InetSocketAddress, KrpcMessage.Parsed> responderObserver;
    /**
     * 服务侧查询处理（KrpcServer 注入；null 期间收到的查询被丢弃——纯客户端模式）。
     * volatile：onQuery 可在接收线程启动后调用。
     */
    private volatile @Nullable BiConsumer<InetSocketAddress, KrpcMessage.Parsed> queryHandler;

    KrpcRpc(int port, BiConsumer<InetSocketAddress, KrpcMessage.Parsed> responderObserver)
            throws IOException {
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(250);
        this.responderObserver = responderObserver;
        Thread.ofVirtual().name("THUNDER-DHT").start(this::receiveLoop);
    }

    int port() {
        return socket.getLocalPort();
    }

    /**
     * 注册服务侧查询处理（KrpcServer 构造时调用；回复经 {@link #sendQuery} 原路发回）。
     */
    void onQuery(BiConsumer<InetSocketAddress, KrpcMessage.Parsed> handler) {
        this.queryHandler = handler;
    }

    /**
     * 阻塞式请求-响应：登记事务 future，等接收线程按事务 ID 配对完成；超时/失败返回 null。
     */
    KrpcMessage.Parsed roundTrip(InetSocketAddress address, KrpcMessage.Builder query) {
        CompletableFuture<KrpcMessage.Parsed> future = new CompletableFuture<>();
        query = register(query, future);
        byte[] transactionId = query.transactionId();
        try {
            byte[] wire = query.encode();
            socket.send(new DatagramPacket(wire, wire.length, address.getAddress(), address.getPort()));
            return future.get(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | IOException | ExecutionException e) {
            return null;
        } finally {
            transactions.remove(key(transactionId));
        }
    }

    /**
     * 以 putIfAbsent 登记事务并返回生效的查询（tid 可能已换）。2 字节 tid 在并发
     * roundTrip 下有生日碰撞，直接 put 会令后写者顶掉在途事务的 future（前者白等
     * 2s 超时）；碰撞时重新生成 ID 并按新 tid 重建查询重试。重试上限后仍撞（事务表
     * 被长事务占满的极端情形）则退回覆盖语义——被顶掉方由其 2s 超时兜底，好过无界
     * 重试阻塞调用方。重建走"编码→解析→再编码"：Builder 的 tid 在构造时已烘进
     * bencode 字典、无替换入口，不值得为此扩 Builder API；碰撞是稀有路径，成本可忽略。
     */
    private KrpcMessage.Builder register(KrpcMessage.Builder query,
            CompletableFuture<KrpcMessage.Parsed> future) {
        for (int attempt = 0; attempt < TRANSACTION_ID_RETRIES; attempt++) {
            if (transactions.putIfAbsent(key(query.transactionId()), future) == null) {
                return query;
            }
            query = rebindTransactionId(query, newTransactionId());
        }
        transactions.put(key(query.transactionId()), future);
        return query;
    }

    /** 按新 tid 重建等价查询（方法与参数逐项搬运，线格式除 t 外一致）。 */
    private static KrpcMessage.Builder rebindTransactionId(KrpcMessage.Builder query,
            byte[] newTransactionId) {
        KrpcMessage.Parsed self = KrpcMessage.parse(query.encode());
        KrpcMessage.Builder rebound = KrpcMessage.Builder.query(newTransactionId,
                self.method() == null ? "" : self.method());
        if (self.args() != null) {
            self.args().value().forEach((name, value) -> rebound.arg(name.text(), value));
        }
        return rebound;
    }

    /**
     * 发后不管（bootstrap 探测）：响应由接收线程经 observer 进路由表。
     */
    void sendQuery(InetSocketAddress address, KrpcMessage.Builder query) {
        try {
            byte[] wire = query.encode();
            socket.send(new DatagramPacket(wire, wire.length, address.getAddress(), address.getPort()));
        } catch (IOException e) {
            log.debug("dht send to {} failed: {}", address, e.toString());
        }
    }

    byte[] newTransactionId() {
        byte[] id = new byte[TRANSACTION_ID_BYTES];
        random.nextBytes(id);
        return id;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed.get()) {
            try {
                socket.receive(packet);
                handlePacket(packet);
            } catch (SocketTimeoutException ignored) {
                // 周期唤醒检查 closed
            } catch (IOException e) {
                if (closed.get()) {
                    return;
                }
                // Windows 上向死端口发送后的 ICMP 不可达会让下一次 receive 抛
                // SocketException(Connection reset)；杂音/瞬时错误不应终止接收线程
                log.debug("dht receive failed (continuing): {}", e.toString());
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
        KrpcMessage.Parsed message;
        try {
            message = KrpcMessage.parse(data);
        } catch (IllegalArgumentException e) {
            return; // 非 KRPC 流量（同端口杂音），忽略
        }
        if ("q".equals(message.type())) {
            // 他人查询交服务侧应答（事务配对只针对我们发起的事务，其应答是 r/e）
            BiConsumer<InetSocketAddress, KrpcMessage.Parsed> handler = queryHandler;
            if (handler != null) {
                handler.accept(
                        new InetSocketAddress(packet.getAddress(), packet.getPort()), message);
            }
            return;
        }
        if (!"r".equals(message.type()) && !"e".equals(message.type())) {
            return;
        }
        responderObserver.accept(
                new InetSocketAddress(packet.getAddress(), packet.getPort()), message);
        CompletableFuture<KrpcMessage.Parsed> future = transactions.remove(key(message.transactionId()));
        if (future != null) {
            future.complete(message);
        }
    }

    private static String key(byte[] transactionId) {
        return HexFormat.of().formatHex(transactionId);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.close();
        }
    }
}
