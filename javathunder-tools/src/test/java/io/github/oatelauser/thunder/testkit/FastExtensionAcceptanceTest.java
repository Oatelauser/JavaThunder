package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.SeedOptions;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.core.internal.peer.PeerConnection;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.HaveAll;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireCodec;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 6 快速扩展验收（真实引擎）：做种会话对声明快速扩展的对端以 HaveAll 替代整幅
 * 位图；对未声明（保留位为零）的传统对端仍按 BEP 3 发位图——协商门槛双向成立。
 */
class FastExtensionAcceptanceTest {

    @TempDir
    Path tempDir;

    @Test
    void fastPeerReceivesHaveAllFromSeedingSession() throws Exception {
        Random random = new Random(3);
        TorrentGenerator.GeneratedTorrent seed = TorrentGenerator.generate(tempDir, "fast.bin",
                256 * 1024, 64 * 1024, "http://127.0.0.1:1/announce", random);
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
        int port = 18000 + random.nextInt(4000);
        try (TorrentClient client = TorrentClient.builder()
                .transport(Transports.select())
                .listenPort(port)
                .build()) {
            DownloadTask task = client.seed(seed.torrentFile(), SeedOptions.defaults().dataDir(tempDir));
            awaitSeeding(task);
            try (PeerConnection peer = PeerConnection.connect(
                    new InetSocketAddress("127.0.0.1", port), meta.infoHash(), PeerIds.generate(), 5000)) {
                assertTrue(peer.remoteSupportsFast(), "引擎侧握手应声明 BEP 6");
                PeerWireMessage first = peer.read();
                assertTrue(first instanceof HaveAll, "做种会话对 fast 对端首帧应为 HaveAll，实为 " + first);
            }
            task.cancel(false);
        }
    }

    @Test
    void legacyPeerStillReceivesBitfield() throws Exception {
        Random random = new Random(5);
        TorrentGenerator.GeneratedTorrent seed = TorrentGenerator.generate(tempDir, "legacy.bin",
                256 * 1024, 64 * 1024, "http://127.0.0.1:1/announce", random);
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
        int port = 18500 + random.nextInt(4000);
        try (TorrentClient client = TorrentClient.builder()
                .transport(Transports.select())
                .listenPort(port)
                .build()) {
            DownloadTask task = client.seed(seed.torrentFile(), SeedOptions.defaults().dataDir(tempDir));
            awaitSeeding(task);
            PeerWireMessage first = readFirstFrameFromLegacyPeer(meta, port);
            assertTrue(first instanceof BitfieldMessage,
                    "未声明 BEP 6 的对端应收到 BEP 3 位图，实为 " + first);
            task.cancel(false);
        }
    }

    /** 原始套接字：保留位全零的握手（传统客户端形态），读回首帧并解码。 */
    private PeerWireMessage readFirstFrameFromLegacyPeer(TorrentMetadata meta, int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            byte[] handshake = new byte[68];
            handshake[0] = 19;
            System.arraycopy("BitTorrent protocol".getBytes("US-ASCII"), 0, handshake, 1, 19);
            System.arraycopy(meta.infoHash(), 0, handshake, 28, 20);
            System.arraycopy(PeerIds.generate(), 0, handshake, 48, 20);
            out.write(handshake);
            out.flush();
            InputStream in = socket.getInputStream();
            readFully(in, 68); // 引擎回握（内容不必检查）
            byte[] header = readFully(in, 4);
            int length = ByteBuffer.wrap(header).getInt();
            byte[] payload = readFully(in, length);
            ByteBuffer frame = ByteBuffer.allocate(4 + length);
            frame.put(header).put(payload).flip();
            return PeerWireCodec.decodeFrame(frame);
        }
    }

    private static void awaitSeeding(DownloadTask task) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (task.state() != TaskState.SEEDING && System.currentTimeMillis() < deadline) {
            if (task.state() == TaskState.FAILED) {
                throw new IllegalStateException("seed task failed");
            }
            Thread.sleep(50);
        }
        assertEquals(TaskState.SEEDING, task.state());
    }

    private static byte[] readFully(InputStream in, int length) throws Exception {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) {
                throw new IllegalStateException("eof after " + off + "/" + length);
            }
            off += n;
        }
        return data;
    }
}
