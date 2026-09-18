package io.github.oatelauser.thunder.core.internal.peer;

import io.github.oatelauser.thunder.core.internal.wire.Handshake;
import io.github.oatelauser.thunder.core.internal.wire.KeepAlive;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireCodec;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 一条 Peer 连接（阻塞 Socket，由调用方在虚拟线程中驱动）。
 * 读超时 {@value #READ_TIMEOUT_MILLIS} ms 内无任何帧则抛 {@link SocketTimeoutException}，
 * 引擎据此回收僵死连接。write 串行化：协议线程与 choking 定时器可并发写。
 */
public final class PeerConnection implements AutoCloseable {

    public static final int READ_TIMEOUT_MILLIS = 120_000;
    public static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    /** BEP 3：帧长度前缀恒 4 字节大端。 */
    private static final int LENGTH_PREFIX_BYTES = 4;
    /** BEP 3 握手定长 68 = 1(pstrlen) + 19(协议串) + 8(保留位) + 20(info-hash) + 20(peer-id)。 */
    private static final int HANDSHAKE_WIRE_BYTES = 68;
    /** socket 读写缓冲：单块 16KiB，32KiB 可容纳一个完整块的多数读写。 */
    private static final int IO_BUFFER_BYTES = 32 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final byte[] remotePeerId;
    private final InetSocketAddress remoteAddress;
    /**
     * 对端握手保留位是否声明 BEP 10 扩展协议（established 旧重载默认 false）。
     */
    private volatile boolean remoteSupportsExtensions;
    /**
     * 对端握手保留位是否声明 BEP 6 快速扩展。
     */
    private volatile boolean remoteSupportsFast;
    /**
     * 对端握手保留位是否声明 BEP 52 v2 协议（reserved[7]&0x10）。
     */
    private volatile boolean remoteSupportsV2;

    private PeerConnection(Socket socket, byte[] remotePeerId) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), IO_BUFFER_BYTES);
        this.out = new BufferedOutputStream(socket.getOutputStream(), IO_BUFFER_BYTES);
        this.remotePeerId = remotePeerId;
        this.remoteAddress = new InetSocketAddress(
                socket.getInetAddress().getHostAddress(), socket.getPort());
    }

    /**
     * 主动连接：先发握手再收对端握手，info-hash 不符立即断开。
     */
    public static PeerConnection connect(InetSocketAddress address, byte[] infoHash, byte[] peerId,
            int connectTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(address, connectTimeoutMillis);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            OutputStream rawOut = socket.getOutputStream();
            rawOut.write(Handshake.encode(infoHash, peerId));
            rawOut.flush();
            byte[] remoteWire = readFully(socket.getInputStream(), HANDSHAKE_WIRE_BYTES);
            Handshake handshake = Handshake.decode(remoteWire);
            if (!Arrays.equals(handshake.infoHash(), infoHash)) {
                throw new IOException("peer " + address + " answered with a different info-hash");
            }
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            PeerConnection connection = new PeerConnection(socket, handshake.peerId());
            captureRemoteCapabilities(connection, remoteWire);
            return connection;
        } catch (IOException e) {
            throw closeSuppressed(socket, e);
        }
    }

    /**
     * 入站连接：先收对端握手（校验 info-hash），再回自己的握手。
     */
    public static PeerConnection accept(Socket socket, byte[] infoHash, byte[] peerId) throws IOException {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            byte[] remoteWire = readFully(socket.getInputStream(), HANDSHAKE_WIRE_BYTES);
            Handshake handshake = Handshake.decode(remoteWire);
            PeerConnection connection = acceptWithHandshake(socket, handshake, infoHash, peerId);
            captureRemoteCapabilities(connection, remoteWire);
            return connection;
        } catch (IOException e) {
            throw closeSuppressed(socket, e);
        }
    }

    /**
     * 入站连接（对端握手已由路由方预读并校验）。
     */
    public static PeerConnection acceptWithHandshake(Socket socket, Handshake remote,
            byte[] infoHash, byte[] peerId) throws IOException {
        try {
            socket.getOutputStream().write(Handshake.encode(infoHash, peerId));
            socket.getOutputStream().flush();
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            return new PeerConnection(socket, remote.peerId());
        } catch (IOException e) {
            throw closeSuppressed(socket, e);
        }
    }

    /**
     * 用已完成握手的 socket 包装连接（入站路由路径用）。
     */
    public static PeerConnection established(Socket socket, byte[] remotePeerId) throws IOException {
        return established(socket, remotePeerId, false, false, false);
    }

    /**
     * 同上，但携带对端握手的保留位声明（调用方已读过对端握手线格式；BEP 10、BEP 6
     * 与 BEP 52）。
     */
    public static PeerConnection established(Socket socket, byte[] remotePeerId,
            boolean remoteSupportsExtensions, boolean remoteSupportsFast,
            boolean remoteSupportsV2) throws IOException {
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        PeerConnection connection = new PeerConnection(socket, remotePeerId);
        connection.remoteSupportsExtensions = remoteSupportsExtensions;
        connection.remoteSupportsFast = remoteSupportsFast;
        connection.remoteSupportsV2 = remoteSupportsV2;
        return connection;
    }

    /**
     * 阻塞读一帧；EOF 抛 IOException。
     */
    public PeerWireMessage read() throws IOException {
        byte[] header = readFully(in, LENGTH_PREFIX_BYTES);
        long length = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
        if (length == 0) {
            return KeepAlive.INSTANCE;
        }
        if (length > PeerWireCodec.MAX_FRAME_BYTES) {
            throw new IOException("peer sent frame of " + length + " bytes, exceeds limit");
        }
        byte[] payload = readFully(in, (int) length);
        ByteBuffer frame = ByteBuffer.allocate(LENGTH_PREFIX_BYTES + (int) length);
        frame.put(header).put(payload).flip();
        return PeerWireCodec.decodeFrame(frame);
    }

    public synchronized void write(PeerWireMessage message) throws IOException {
        out.write(PeerWireCodec.encode(message));
        out.flush();
    }

    public byte[] remotePeerId() {
        return remotePeerId.clone();
    }

    /**
     * 对端握手保留位是否声明支持 BEP 10 扩展协议。
     */
    public boolean remoteSupportsExtensions() {
        return remoteSupportsExtensions;
    }

    /**
     * 对端握手保留位是否声明支持 BEP 6 快速扩展。
     */
    public boolean remoteSupportsFast() {
        return remoteSupportsFast;
    }

    /**
     * 对端握手保留位是否声明支持 BEP 52 v2 协议（哈希交换）。
     */
    public boolean remoteSupportsV2() {
        return remoteSupportsV2;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** 从对端握手线格式提取保留位声明（BEP 10/6/52），缓存到连接上供后续查询。 */
    private static void captureRemoteCapabilities(PeerConnection connection, byte[] remoteWire) {
        connection.remoteSupportsExtensions = Handshake.supportsExtensions(remoteWire);
        connection.remoteSupportsFast = Handshake.supportsFastExtension(remoteWire);
        connection.remoteSupportsV2 = Handshake.supportsV2(remoteWire);
    }

    /** 建连/握手失败即弃 socket：关闭时的新异常挂到 suppressed，原异常继续上抛。 */
    private static IOException closeSuppressed(Socket socket, IOException cause) {
        try {
            socket.close();
        } catch (IOException suppressed) {
            cause.addSuppressed(suppressed);
        }
        return cause;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) {
                throw new IOException("connection closed by peer after " + off + "/" + length + " bytes");
            }
            off += n;
        }
        return data;
    }
}
