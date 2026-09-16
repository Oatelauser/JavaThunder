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

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final byte[] remotePeerId;
    private final InetSocketAddress remoteAddress;
    /**
     * 对端握手保留位是否声明 BEP 10 扩展协议（established 旧重载默认 false）。
     */
    private volatile boolean remoteSupportsExtensions;

    private PeerConnection(Socket socket, byte[] remotePeerId) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
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
            byte[] remoteWire = readFully(socket.getInputStream(), 68);
            Handshake handshake = Handshake.decode(remoteWire);
            if (!Arrays.equals(handshake.infoHash(), infoHash)) {
                throw new IOException("peer " + address + " answered with a different info-hash");
            }
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            PeerConnection connection = new PeerConnection(socket, handshake.peerId());
            connection.remoteSupportsExtensions = Handshake.supportsExtensions(remoteWire);
            return connection;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * 入站连接：先收对端握手（校验 info-hash），再回自己的握手。
     */
    public static PeerConnection accept(Socket socket, byte[] infoHash, byte[] peerId) throws IOException {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            byte[] remoteWire = readFully(socket.getInputStream(), 68);
            Handshake handshake = Handshake.decode(remoteWire);
            PeerConnection connection = acceptWithHandshake(socket, handshake, infoHash, peerId);
            connection.remoteSupportsExtensions = Handshake.supportsExtensions(remoteWire);
            return connection;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
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
            try {
                socket.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * 用已完成握手的 socket 包装连接（入站路由路径用）。
     */
    public static PeerConnection established(Socket socket, byte[] remotePeerId) throws IOException {
        return established(socket, remotePeerId, false);
    }

    /**
     * 同上，但携带对端握手的 BEP 10 保留位声明（调用方已读过对端握手线格式）。
     */
    public static PeerConnection established(Socket socket, byte[] remotePeerId,
            boolean remoteSupportsExtensions) throws IOException {
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        PeerConnection connection = new PeerConnection(socket, remotePeerId);
        connection.remoteSupportsExtensions = remoteSupportsExtensions;
        return connection;
    }

    /**
     * 阻塞读一帧；EOF 抛 IOException。
     */
    public PeerWireMessage read() throws IOException {
        byte[] header = readFully(in, 4);
        long length = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
        if (length == 0) {
            return KeepAlive.INSTANCE;
        }
        if (length > PeerWireCodec.MAX_FRAME_BYTES) {
            throw new IOException("peer sent frame of " + length + " bytes, exceeds limit");
        }
        byte[] payload = readFully(in, (int) length);
        ByteBuffer frame = ByteBuffer.allocate(4 + (int) length);
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

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public void close() throws IOException {
        socket.close();
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
