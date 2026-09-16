package io.github.oatelauser.thunder.core.internal.peer.transport;

import io.github.oatelauser.thunder.core.internal.wire.Handshake;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireCodec;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * NIO 事件循环传输（ADR-0003）：单 Selector 平台线程承载全部连接——
 * OP_ACCEPT/OP_CONNECT/OP_READ/OP_WRITE，每连接堆缓冲合帧批解码，
 * 写队列 gather 批刷 + OP_WRITE 背压，毫秒级空闲超时管理。
 * 消息在 Selector 线程上回调（引擎侧重活已卸载到虚拟线程 worker）。
 */
public final class NioTransport implements PeerTransport {

    private static final Logger log = LoggerFactory.getLogger(NioTransport.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 8000;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final int IDLE_TIMEOUT_MILLIS = 120_000;
    private static final int SELECT_TICK_MILLIS = 1000;
    private static final int READ_BUFFER_INITIAL = 16 * 1024;
    private static final int WRITE_BATCH_MAX = 64;

    private final byte[] peerId;
    private final Selector selector;
    private final Thread selectorThread;
    private final Queue<Runnable> selectorTasks = new ConcurrentLinkedQueue<>();
    private final Set<NioChannel> channels = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile ServerSocketChannel serverChannel;
    private volatile int listeningPort = -1;
    private volatile boolean closed;

    public NioTransport(byte[] peerId) {
        this.peerId = peerId.clone();
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new IllegalStateException("cannot open selector", e);
        }
        this.selectorThread = Thread.ofPlatform().name("THUNDER-NIO").daemon(true).start(this::eventLoop);
    }

    @Override
    public int listen(int preferredPort, HandshakeRouter router) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        submit(() -> {
            try {
                ServerSocketChannel server = ServerSocketChannel.open();
                server.configureBlocking(false);
                server.bind(new InetSocketAddress(preferredPort), 256);
                server.register(selector, SelectionKey.OP_ACCEPT, router);
                serverChannel = server;
                listeningPort = ((InetSocketAddress) server.getLocalAddress()).getPort();
            } catch (IOException e) {
                failure.set(new IllegalStateException("cannot bind port " + preferredPort, e));
            }
        });
        while (listeningPort < 0 && failure.get() == null && !closed) {
            sleepMillis(2);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        if (listeningPort < 0) {
            throw new IllegalStateException("transport closed before listen completed");
        }
        return listeningPort;
    }

    @Override
    public void connect(InetSocketAddress address, byte[] infoHash, TransportHandler handler) {
        try {
            SocketChannel socket = SocketChannel.open();
            socket.configureBlocking(false);
            socket.connect(address);
            NioChannel channel = new NioChannel(socket, System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS);
            submit(() -> {
                try {
                    channel.key = socket.register(selector, SelectionKey.OP_CONNECT, channel);
                    channel.pendingInfoHash = infoHash.clone();
                    channel.pendingHandler = handler;
                    channels.add(channel);
                } catch (ClosedChannelException e) {
                    handler.onConnectFailed(address, e);
                }
            });
        } catch (IOException e) {
            handler.onConnectFailed(address, e);
        }
    }

    @Override
    public int listeningPort() {
        return listeningPort;
    }

    @Override
    public void close() {
        closed = true;
        selector.wakeup();
        for (NioChannel channel : channels) {
            channel.closeWith(null);
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
            selector.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- event loop

    private void eventLoop() {
        while (!closed) {
            runPendingTasks();
            try {
                selector.select(SELECT_TICK_MILLIS);
            } catch (IOException e) {
                return;
            }
            if (closed) {
                return;
            }
            processReadyKeys();
            processTimeouts();
        }
    }

    private void runPendingTasks() {
        Runnable task;
        while ((task = selectorTasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("selector task failed", e);
            }
        }
    }

    private void processReadyKeys() {
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) {
                continue;
            }
            try {
                if (key.isAcceptable()) {
                    acceptAll((HandshakeRouter) key.attachment());
                } else {
                    NioChannel channel = (NioChannel) key.attachment();
                    if (key.isConnectable()) {
                        channel.finishConnect();
                    }
                    if (key.isValid() && key.isReadable()) {
                        channel.onReadable();
                    }
                    if (key.isValid() && key.isWritable()) {
                        channel.flushWrites();
                    }
                }
            } catch (IOException | RuntimeException e) {
                ((NioChannel) key.attachment()).closeWith(e);
            }
        }
    }

    private void acceptAll(HandshakeRouter router) {
        while (true) {
            SocketChannel socket;
            try {
                socket = serverChannel.accept();
            } catch (IOException e) {
                return;
            }
            if (socket == null) {
                return;
            }
            try {
                socket.configureBlocking(false);
                NioChannel channel = new NioChannel(socket, System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MILLIS);
                channel.phase = Phase.HANDSHAKE; // 入站：等待对端先发握手
                channel.inboundRouter = router;
                channel.key = socket.register(selector, SelectionKey.OP_READ, channel);
                channels.add(channel);
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
    }

    private void processTimeouts() {
        long now = System.currentTimeMillis();
        for (NioChannel channel : channels) {
            long deadline = channel.phase == Phase.ESTABLISHED
                    ? channel.lastActivity + IDLE_TIMEOUT_MILLIS
                    : channel.deadline;
            if (now > deadline) {
                channel.closeWith(new IOException("timeout in phase " + channel.phase));
            }
        }
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        selectorTasks.offer(task);
        selector.wakeup();
    }

    // ---------------------------------------------------------------- channel

    private enum Phase {
        CONNECTING, HANDSHAKE, ESTABLISHED
    }

    final class NioChannel implements PeerChannel {
        final SocketChannel socket;
        volatile SelectionKey key;
        volatile Phase phase = Phase.CONNECTING;
        volatile long lastActivity = System.currentTimeMillis();
        final long deadline;

        byte[] pendingInfoHash;
        TransportHandler pendingHandler;
        HandshakeRouter inboundRouter;
        byte[] remotePeerIdValue;
        InetSocketAddress remoteAddressValue;

        // 缓冲区恒为"读态"（flip 后）：compact 把未消费数据搬到头部并腾出空间。
        // 出生即 flip 建立不变式，否则首次 compact 会把未定义数据当作未消费内容。
        ByteBuffer readBuffer = flippedAllocate(READ_BUFFER_INITIAL);
        final ArrayDeque<ByteBuffer> writeQueue = new ArrayDeque<>();
        final ByteBuffer[] writeBatch = new ByteBuffer[WRITE_BATCH_MAX];
        private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
        private volatile Consumer<List<PeerWireMessage>> messageListener = m -> {
        };
        private volatile Consumer<@Nullable Throwable> closeListener = t -> {
        };
        volatile boolean closed;
        volatile boolean remoteSupportsExtensions;

        NioChannel(SocketChannel socket, long deadline) {
            this.socket = socket;
            this.deadline = deadline;
        }

        @Override
        public boolean remoteSupportsExtensions() {
            return remoteSupportsExtensions;
        }

        void finishConnect() throws IOException {
            if (!socket.finishConnect()) {
                return;
            }
            remoteAddressValue = (InetSocketAddress) socket.getRemoteAddress();
            writeQueue.add(ByteBuffer.wrap(Handshake.encode(pendingInfoHash, peerId)));
            phase = Phase.HANDSHAKE;
            scheduleFlush();
            interestOps(SelectionKey.OP_READ);
        }

        void onReadable() throws IOException {
            lastActivity = System.currentTimeMillis();
            readBuffer.compact();
            int read = socket.read(readBuffer);
            if (read < 0) {
                closeWith(new IOException("connection closed by peer"));
                return;
            }
            readBuffer.flip();
            if (phase == Phase.HANDSHAKE) {
                if (readBuffer.remaining() < 68) {
                    return;
                }
                byte[] wire = new byte[68];
                readBuffer.get(wire);
                Handshake handshake = Handshake.decode(wire);
                boolean remoteExt = Handshake.supportsExtensions(wire);
                if (pendingHandler != null) {
                    // 出站：校验 info-hash，通道就绪
                    if (!Arrays.equals(handshake.infoHash(), pendingInfoHash)) {
                        closeWith(new IOException("peer answered with a different info-hash"));
                        return;
                    }
                    established(handshake, pendingHandler, remoteExt);
                } else {
                    // 入站：先路由，再回握
                    TransportHandler handler = inboundRouter.route(handshake.infoHash());
                    if (handler == null) {
                        closeWith(null);
                        return;
                    }
                    writeQueue.add(ByteBuffer.wrap(Handshake.encode(handshake.infoHash(), peerId)));
                    scheduleFlush();
                    established(handshake, handler, remoteExt);
                }
            }
            deliverFrames();
        }

        private void established(Handshake handshake, TransportHandler handler,
                boolean remoteSupportsExtensions) {
            remotePeerIdValue = handshake.peerId();
            this.remoteSupportsExtensions = remoteSupportsExtensions;
            if (remoteAddressValue == null) {
                try {
                    remoteAddressValue = (InetSocketAddress) socket.getRemoteAddress();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            phase = Phase.ESTABLISHED;
            handler.onConnected(this); // 监听器在此回调内挂好
        }

        /**
         * 批量投递：一次读批的全部帧合成一个 List 一次回调（批内线序保持）。
         */
        private void deliverFrames() {
            List<PeerWireMessage> batch = new ArrayList<>();
            while (readBuffer.remaining() >= 4) {
                int length = peekLength(readBuffer);
                int frameSize = 4 + length;
                if (frameSize > readBuffer.capacity()) {
                    growReadBuffer(frameSize);
                }
                if (readBuffer.remaining() < frameSize) {
                    break; // 半帧，等下次 read
                }
                ByteBuffer frame = readBuffer.slice();
                frame.limit(frameSize);
                try {
                    batch.add(PeerWireCodec.decodeFrame(frame));
                } catch (RuntimeException e) {
                    closeWith(new IOException("malformed frame: " + e.getMessage()));
                    return;
                }
                readBuffer.position(readBuffer.position() + frameSize);
            }
            if (!batch.isEmpty()) {
                messageListener.accept(batch);
            }
        }

        @Override
        public void write(PeerWireMessage message) {
            if (closed) {
                return;
            }
            synchronized (writeQueue) {
                writeQueue.add(ByteBuffer.wrap(PeerWireCodec.encode(message)));
            }
            scheduleFlush();
        }

        @Override
        public void write(List<PeerWireMessage> messages) {
            if (closed || messages.isEmpty()) {
                return;
            }
            int capacity = messages.size() * 17; // request/keepalive 级小帧的保守上界
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(capacity);
            for (PeerWireMessage message : messages) {
                encoded.writeBytes(PeerWireCodec.encode(message));
            }
            synchronized (writeQueue) {
                writeQueue.add(ByteBuffer.wrap(encoded.toByteArray()));
            }
            scheduleFlush();
        }

        void scheduleFlush() {
            if (closed || flushScheduled.compareAndSet(false, true)) {
                if (closed) {
                    return;
                }
                submit(() -> {
                    flushScheduled.set(false);
                    flushWrites();
                });
            }
        }

        void flushWrites() {
            if (closed) {
                return;
            }
            lastActivity = System.currentTimeMillis();
            try {
                while (true) {
                    int count = 0;
                    synchronized (writeQueue) {
                        for (ByteBuffer buffer : writeQueue) {
                            if (count == WRITE_BATCH_MAX) {
                                break;
                            }
                            if (buffer.hasRemaining()) {
                                writeBatch[count++] = buffer;
                            } else {
                                break; // 队头已发完（flush 后会清理）
                            }
                        }
                    }
                    if (count == 0) {
                        break;
                    }
                    long written = socket.write(writeBatch, 0, count);
                    if (written == 0) {
                        break; // 对端慢：注册 OP_WRITE 等可写
                    }
                    synchronized (writeQueue) {
                        while (!writeQueue.isEmpty() && !writeQueue.peek().hasRemaining()) {
                            writeQueue.poll();
                        }
                    }
                }
                boolean backlogRemaining;
                synchronized (writeQueue) {
                    backlogRemaining = writeQueue.stream().anyMatch(ByteBuffer::hasRemaining);
                }
                if (backlogRemaining) {
                    interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                } else {
                    interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
            } catch (IOException e) {
                closeWith(e);
            }
        }

        private void interestOps(int ops) {
            SelectionKey current = key;
            if (current != null && current.isValid()) {
                current.interestOps(ops);
            }
        }

        private void growReadBuffer(int required) {
            ByteBuffer grown = ByteBuffer.allocate(required); // 未翻转态（limit=capacity）以容纳 put
            grown.put(readBuffer);
            grown.flip(); // 回到读态不变式
            readBuffer = grown;
        }

        private static ByteBuffer flippedAllocate(int capacity) {
            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            buffer.flip();
            return buffer;
        }

        private static int peekLength(ByteBuffer buffer) {
            return ((buffer.get(buffer.position()) & 0xFF) << 24)
                    | ((buffer.get(buffer.position() + 1) & 0xFF) << 16)
                    | ((buffer.get(buffer.position() + 2) & 0xFF) << 8)
                    | (buffer.get(buffer.position() + 3) & 0xFF);
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return remoteAddressValue;
        }

        @Override
        public byte[] remotePeerId() {
            return remotePeerIdValue == null ? new byte[20] : remotePeerIdValue.clone();
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

        void closeWith(@Nullable Throwable cause) {
            if (closed) {
                return;
            }
            closed = true;
            channels.remove(this);
            SelectionKey current = key;
            if (current != null) {
                current.cancel();
            }
            closeQuietly(socket);
            if (pendingHandler != null && phase != Phase.ESTABLISHED) {
                pendingHandler.onConnectFailed(remoteAddressValue == null
                        ? new InetSocketAddress(0) : remoteAddressValue, cause == null
                        ? new IOException("closed before established") : cause);
            }
            log.debug("nio channel {} closed: {}", remoteAddressValue,
                    cause == null ? "local close" : cause.toString());
            closeListener.accept(cause);
        }
    }

    private static void closeQuietly(SocketChannel socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
