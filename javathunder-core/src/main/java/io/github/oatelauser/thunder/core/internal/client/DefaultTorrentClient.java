package io.github.oatelauser.thunder.core.internal.client;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.engine.DownloadSession;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.core.internal.peer.PeerConnection;
import io.github.oatelauser.thunder.core.internal.ratelimit.RateLimiter;
import io.github.oatelauser.thunder.core.internal.tracker.PeerIds;
import io.github.oatelauser.thunder.core.internal.tracker.TrackerClient;
import io.github.oatelauser.thunder.core.internal.wire.Handshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link TorrentClient} 默认实现：全局资源 + 多任务编排 + 入站连接路由。 */
public final class DefaultTorrentClient implements TorrentClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultTorrentClient.class);

    public static Builder builder() {
        return new Builder();
    }

    /** 便捷工厂：全部默认值。 */
    public static DefaultTorrentClient create() throws IOException {
        return builder().build();
    }

    public static final class Builder {
        private int listenPort = 6881;
        private int maxConcurrentTasks = 3;
        private int maxPeersPerTask = 50;
        private long downloadLimitBytesPerSecond;
        private long uploadLimitBytesPerSecond;
        private Executor listenerExecutor;

        public Builder listenPort(int port) {
            this.listenPort = port;
            return this;
        }

        public Builder maxConcurrentTasks(int max) {
            this.maxConcurrentTasks = max;
            return this;
        }

        public Builder maxPeersPerTask(int max) {
            this.maxPeersPerTask = max;
            return this;
        }

        public Builder downloadLimitBytesPerSecond(long bytesPerSecond) {
            this.downloadLimitBytesPerSecond = bytesPerSecond;
            return this;
        }

        public Builder uploadLimitBytesPerSecond(long bytesPerSecond) {
            this.uploadLimitBytesPerSecond = bytesPerSecond;
            return this;
        }

        /** 注入自定义监听器回调线程；缺省为库内单线程守护线程。 */
        public Builder listenerExecutor(Executor executor) {
            this.listenerExecutor = executor;
            return this;
        }

        public DefaultTorrentClient build() throws IOException {
            return new DefaultTorrentClient(this);
        }
    }

    private final int maxPeersPerTask;
    private final Semaphore slots;
    private final Executor eventExecutor;
    private final ExecutorService ownedExecutor;
    private final TrackerClient trackerClient = new TrackerClient();
    private final RateLimiter globalDownload;
    private final RateLimiter globalUpload;
    private final byte[] peerId = PeerIds.generate();
    private final ServerSocket listenSocket;
    private final ConcurrentHashMap<String, DownloadSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DefaultTorrentClient(Builder builder) throws IOException {
        this.maxPeersPerTask = builder.maxConcurrentTasks > 0 ? builder.maxPeersPerTask : 50;
        this.slots = new Semaphore(Math.max(1, builder.maxConcurrentTasks));
        this.globalDownload = builder.downloadLimitBytesPerSecond <= 0
            ? RateLimiter.unlimited() : new RateLimiter(builder.downloadLimitBytesPerSecond);
        this.globalUpload = builder.uploadLimitBytesPerSecond <= 0
            ? RateLimiter.unlimited() : new RateLimiter(builder.uploadLimitBytesPerSecond);
        if (builder.listenerExecutor != null) {
            this.ownedExecutor = null;
            this.eventExecutor = builder.listenerExecutor;
        } else {
            this.ownedExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "javathunder-events");
                thread.setDaemon(true);
                return thread;
            });
            this.eventExecutor = ownedExecutor;
        }
        this.listenSocket = new ServerSocket(builder.listenPort);
        Thread.ofPlatform().name("javathunder-accept").daemon(true).start(this::acceptLoop);
    }

    @Override
    public DownloadTask download(Path torrentFile, DownloadOptions options) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(torrentFile));
        slots.acquire();
        DownloadSession session;
        try {
            session = new DownloadSession(meta, options,
                new DownloadSession.SessionConfig(maxPeersPerTask, listenSocket.getLocalPort(),
                    globalDownload, globalUpload),
                trackerClient, eventExecutor, peerId);
        } catch (IOException | RuntimeException e) {
            slots.release();
            throw e;
        }
        String key = HexFormat.of().formatHex(meta.infoHash());
        sessions.put(key, session);
        DownloadTaskImpl task = new DownloadTaskImpl(session, () -> {
            sessions.remove(key, session);
            slots.release();
        });
        session.start();
        return task;
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = listenSocket.accept();
                socket.setSoTimeout(PeerConnection.HANDSHAKE_TIMEOUT_MILLIS);
                byte[] wire = readFully(socket.getInputStream(), 68);
                Handshake handshake = Handshake.decode(wire);
                DownloadSession session = sessions.get(HexFormat.of().formatHex(handshake.infoHash()));
                if (session == null) {
                    socket.close();
                } else {
                    session.handleInbound(socket, handshake);
                }
            } catch (IOException | RuntimeException e) {
                if (closed.get()) {
                    return;
                }
                log.debug("inbound connection rejected: {}", e.toString());
            }
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (DownloadSession session : sessions.values()) {
            try {
                session.cancel(false);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            listenSocket.close();
        } catch (IOException ignored) {
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }
}
