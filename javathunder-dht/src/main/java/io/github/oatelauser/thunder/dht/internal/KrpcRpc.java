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

/**
 * KRPC 传输层（BEP 5）：单 UDP socket + 接收线程 + 事务表。只负责报文收发与
 * 事务配对（roundTrip 阻塞等待 / sendQuery 发后不管），不含任何查找或路由语义——
 * 响应者观察（路由表登记）经构造器注入的 observer 回调交给上层。
 */
final class KrpcRpc implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KrpcRpc.class);
    private static final int QUERY_TIMEOUT_MILLIS = 2000;
    /** 接收缓冲：KRPC 实践报文 <1.5KB（大 nodes 列表居多数百字节），4KB 留足余量。 */
    private static final int RECEIVE_BUFFER_BYTES = 4096;
    /** 事务 ID 宽度：主流 DHT 实现约定 2 字节；碰撞时后写者覆盖事务表、前者由超时兜底返回 null。 */
    private static final int TRANSACTION_ID_BYTES = 2;

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
     * 阻塞式请求-响应：登记事务 future，等接收线程按事务 ID 配对完成；超时/失败返回 null。
     */
    KrpcMessage.Parsed roundTrip(InetSocketAddress address, KrpcMessage.Builder query) {
        byte[] transactionId = query.transactionId();
        CompletableFuture<KrpcMessage.Parsed> future = new CompletableFuture<>();
        transactions.put(key(transactionId), future);
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
        if (!"r".equals(message.type()) && !"e".equals(message.type())) {
            return; // 我们不响应他人查询（纯客户端模式；阶段后续可加服务侧）
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
