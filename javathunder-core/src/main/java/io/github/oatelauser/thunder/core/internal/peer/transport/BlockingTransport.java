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
import java.util.List;
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
            PeerConnection connection =
                    PeerConnection.established(socket, handshake.peerId(), Handshake.supportsExtensions(wire));
            BlockingChannel channel = new BlockingChannel(connection);
            handler.onConnected(channel);
            channel.start();
        } catch (IOException | RuntimeException e) {
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
     */
    static final class BlockingChannel implements PeerChannel {

        private final PeerConnection connection;
        private volatile Consumer<List<PeerWireMessage>> messageListener = m -> {
        };
        private volatile Consumer<@Nullable Throwable> closeListener = t -> {
        };
        private volatile boolean closed;

        BlockingChannel(PeerConnection connection) {
            this.connection = connection;
        }

        void start() {
            Thread.ofVirtual().name("javathunder-peer-" + remoteAddress()).start(this::readLoop);
        }

        private void readLoop() {
            try {
                while (!closed) {
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

        private synchronized void closeWith(@Nullable Throwable cause) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.close();
            } catch (IOException ignored) {
            }
            log.debug("channel {} closed: {}", remoteAddress(), cause == null ? "local close" : cause.toString());
            closeListener.accept(cause);
        }
    }
}
