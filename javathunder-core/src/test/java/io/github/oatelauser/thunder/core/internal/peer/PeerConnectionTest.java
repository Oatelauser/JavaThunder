package io.github.oatelauser.thunder.core.internal.peer;

import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.Handshake;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireCodec;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 用脚本化假对端（原始 ServerSocket）验证握手与帧收发的端到端行为。 */
class PeerConnectionTest {

    private static final byte[] INFO_HASH = new byte[20];
    private static final byte[] PEER_ID = "-JT0001-localtest001".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER_PEER_ID = "-FAKE01-seedertest01".getBytes(StandardCharsets.US_ASCII);

    static {
        Arrays.fill(INFO_HASH, (byte) 3);
    }

    private final ExecutorService peerThreads = Executors.newVirtualThreadPerTaskExecutor();

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int off = 0;
        while (off < target.length) {
            int n = in.read(target, off, target.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }

    @Test
    void handshakeExchangeAndMessageRoundTrip() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            peerThreads.submit(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    byte[] wire = new byte[68];
                    readFully(in, wire);
                    Handshake handshake = Handshake.decode(wire);
                    if (!Arrays.equals(handshake.infoHash(), INFO_HASH)) {
                        socket.close();
                        return null;
                    }
                    out.write(Handshake.encode(INFO_HASH, SERVER_PEER_ID));
                    out.flush();
                    // 假对端：全量位图 + 立即 unchoke
                    out.write(PeerWireCodec.encode(
                        new BitfieldMessage(new byte[]{(byte) 0xC0})));
                    out.write(PeerWireCodec.encode(
                        Unchoke.INSTANCE));
                    out.flush();
                    // 读取 client 的 Interested，回一个 Piece
                    ByteBufferHelper.readFrame(in, 5); // interested 帧
                    ByteBufferHelper.readFrame(in, 17); // request 帧
                    out.write(PeerWireCodec.encode(
                        new PieceMessage(1, 0, new byte[]{9, 9, 9, 9})));
                    out.flush();
                }
                return null;
            });

            try (PeerConnection connection = PeerConnection.connect(
                new InetSocketAddress("127.0.0.1", server.getLocalPort()),
                INFO_HASH, PEER_ID, 5000)) {

                assertArrayEquals(SERVER_PEER_ID, connection.remotePeerId());
                assertEquals(new BitfieldMessage(new byte[]{(byte) 0xC0}), connection.read());
                assertEquals(Unchoke.INSTANCE, connection.read());

                connection.write(Interested.INSTANCE);
                connection.write(new Request(1, 0, 16384));
                assertEquals(new PieceMessage(1, 0, new byte[]{9, 9, 9, 9}), connection.read());
            }
        }
    }

    @Test
    void handshakeWithWrongInfoHashIsRejected() throws Exception {
        byte[] otherHash = new byte[20];
        Arrays.fill(otherHash, (byte) 7);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            peerThreads.submit(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    byte[] wire = new byte[68];
                    readFully(in, wire);
                    socket.getOutputStream().write(Handshake.encode(otherHash, SERVER_PEER_ID));
                    socket.getOutputStream().flush();
                }
                return null;
            });

            assertThrows(IOException.class, () -> PeerConnection.connect(
                new InetSocketAddress("127.0.0.1", server.getLocalPort()),
                INFO_HASH, PEER_ID, 5000));
        }
    }

    /** 测试辅助：按帧长度前缀吞掉一帧。 */
    private static final class ByteBufferHelper {
        static void readFrame(InputStream in, int totalBytes) throws IOException {
            readFully(in, new byte[totalBytes]);
        }
    }
}
