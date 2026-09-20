package io.github.oatelauser.thunder.core.internal.peer.transport;

import io.github.oatelauser.thunder.core.internal.peer.PeerConnection;
import io.github.oatelauser.thunder.core.internal.wire.Handshake;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 参照实现（ADR-0003）：每连接一条虚拟线程的阻塞式传输。
 * 直线代码、行为明确，作为 NioTransport 的对照与调试工具长期保留。
 */
public final class BlockingTransport implements PeerTransport {

    private static final Logger log = LoggerFactory.getLogger(BlockingTransport.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 8000;
    private static final int LISTEN_BACKLOG = 256;

    private final byte[] peerId;
    private volatile ServerSocket listenSocket;
    private volatile int listeningPort = -1;
    private volatile boolean closed;
    /** 已建通道注册表：close() 时统一关闭（对齐 NioTransport 语义），通道自关闭时移除。 */
    private final Set<BlockingChannel> channels = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public BlockingTransport(byte[] peerId) {
        this.peerId = peerId.clone();
    }

    @Override
    public int listen(int preferredPort, HandshakeRouter router) {
        try {
            ServerSocket serverSocket = new ServerSocket(preferredPort, LISTEN_BACKLOG);
            this.listenSocket = serverSocket;
            this.listeningPort = serverSocket.getLocalPort();
            Thread.ofVirtual().name("THUNDER-ACCEPT").start(() -> acceptLoop(serverSocket, router));
            return listeningPort;
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind port " + preferredPort, e);
        }
    }

    private void acceptLoop(ServerSocket serverSocket, HandshakeRouter router) {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().start(() -> acceptOne(socket, router));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void acceptOne(Socket socket, HandshakeRouter router) {
        try {
            socket.setSoTimeout(PeerConnection.HANDSHAKE_TIMEOUT_MILLIS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] wire = readFully(in, 68);
            Handshake handshake = Handshake.decode(wire);
            TransportHandler handler = router.route(handshake.infoHash());
            if (handler == null) {
                socket.close();
                return;
            }
            out.write(Handshake.encode(handshake.infoHash(), peerId));
            out.flush();
            PeerConnection connection = PeerConnection.established(socket, handshake.peerId(),
                    Handshake.supportsExtensions(wire), Handshake.supportsFastExtension(wire),
                    Handshake.supportsV2(wire));
            BlockingChannel channel = new BlockingChannel(connection);
            channels.add(channel);
            if (closed) {
                // close() 的遍历可能刚错过本通道：自行补一刀，别让对端等 EOF
                channel.closeWith(null);
                return;
            }
            handler.onConnected(channel);
            channel.start();
        } catch (IOException | RuntimeException e) {
            // 握手失败的对端只能弃连；留 debug 痕迹便于排查互操作问题
            log.debug("inbound peer {} failed during handshake: {}", socket.getRemoteSocketAddress(),
                    e.toString());
            closeQuietly(socket);
        }
    }

    @Override
    public void connect(InetSocketAddress address, byte[] infoHash, TransportHandler handler) {
        Thread.ofVirtual().name("javathunder-connect-" + address).start(() -> {
            try {
                PeerConnection connection =
                        PeerConnection.connect(address, infoHash, peerId, CONNECT_TIMEOUT_MILLIS);
                BlockingChannel channel = new BlockingChannel(connection);
                channels.add(channel);
                if (closed) {
                    channel.closeWith(null);
                    return;
                }
                handler.onConnected(channel);
                channel.start();
            } catch (IOException | RuntimeException e) {
                handler.onConnectFailed(address, e);
            }
        });
    }

    @Override
    public int listeningPort() {
        return listeningPort;
    }

    @Override
    public void close() {
        closed = true;
        // 已建连接一并关闭：否则其读虚拟线程滞留在阻塞 read 上，直到对端断开或
        // 120s 读超时（对齐 NioTransport 的 close 语义）。closeWith 幂等，与通道
        // 自关闭路径并发安全（仅争用各通道自身监视器，无传输级锁，不会死锁）。
        for (BlockingChannel channel : channels) {
            channel.closeWith(null);
        }
        try {
            if (listenSocket != null) {
                listenSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) {
                throw new IOException("connection closed during handshake");
            }
            off += n;
        }
        return data;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 阻塞式通道：一条读虚拟线程把消息推给监听器。
     * （内部类：closeWith 须从传输的通道注册表自移除，防注册表随连接数无界增长。）
     */
    final class BlockingChannel implements PeerChannel {

        private final PeerConnection connection;
        private volatile Consumer<List<PeerWireMessage>> messageListener = m -> {
        };
        private volatile Consumer<@Nullable Throwable> closeListener = t -> {
        };
        private final AtomicBoolean closed = new AtomicBoolean(false);

        BlockingChannel(PeerConnection connection) {
            this.connection = connection;
        }

        void start() {
            Thread.ofVirtual().name("javathunder-peer-" + remoteAddress()).start(this::readLoop);
        }

        private void readLoop() {
            try {
                while (!closed.get()) {
                    PeerWireMessage message = connection.read();
                    messageListener.accept(List.of(message));
                }
            } catch (IOException | RuntimeException e) {
                closeWith(e);
            }
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return connection.remoteAddress();
        }

        @Override
        public byte[] remotePeerId() {
            return connection.remotePeerId();
        }

        @Override
        public boolean remoteSupportsExtensions() {
            return connection.remoteSupportsExtensions();
        }

        @Override
        public boolean remoteSupportsFast() {
            return connection.remoteSupportsFast();
        }

        @Override
        public boolean remoteSupportsV2() {
            return connection.remoteSupportsV2();
        }

        @Override
        public void write(PeerWireMessage message) {
            try {
                connection.write(message);
            } catch (IOException | RuntimeException e) {
                closeWith(e);
            }
        }

        @Override
        public void close() {
            closeWith(null);
        }

        @Override
        public void setMessageListener(Consumer<List<PeerWireMessage>> listener) {
            this.messageListener = listener;
        }

        @Override
        public void setCloseListener(Consumer<@Nullable Throwable> listener) {
            this.closeListener = listener;
        }

        /**
         * 关闭通道（幂等，CAS 抢占）：注册表摘除、连接关闭与监听器回调全部在
         * <b>不持任何监视器</b>下执行——closeListener 会进 DownloadSession 的
         * {@code synchronized(session)}，而引擎侧存在持 session 锁调用
         * {@code channel.close()} 的路径：若本方法 synchronized（持 channel 监视器
         * 再等 session 监视器），两侧构成 ABBA 环形等待（MultiPeer 阻塞臂实测死锁，
         * jcmd 虚拟线程转储定位）。并发竞争由 CAS 保证恰好一个线程执行关闭序列。
         */
        private void closeWith(@Nullable Throwable cause) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            channels.remove(this);
            try {
                connection.close();
            } catch (IOException ignored) {
            }
            log.debug("channel {} closed: {}", remoteAddress(), cause == null ? "local close" : cause.toString());
            closeListener.accept(cause);
        }
    }
}
