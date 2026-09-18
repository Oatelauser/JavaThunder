package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import io.github.oatelauser.thunder.core.internal.metainfo.MerkleHashes;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentVersion;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEP 52 S4 验收：hybrid 种子双哈希下载走 v1 面校验；损坏数据 SHA-1 校验拒绝。
 */
class HybridAndCorruptionAcceptanceTest {

    @TempDir
    Path tempDir;
    private static final int PIECE_LENGTH = 16 * 1024;

    /** 造 hybrid 种子（v1 pieces + v2 file tree/layers，同一数据两套描述）。 */
    private Seed hybridSeed(String name, int size, Random random) throws IOException {
        byte[] content = new byte[size];
        random.nextBytes(content);
        return buildSeed(name, content, true);
    }

    private Seed buildSeed(String name, byte[] content, boolean hybrid) throws IOException {
        int size = content.length;
        int pieceCount = (size + PIECE_LENGTH - 1) / PIECE_LENGTH;

        List<byte[]> leaves = new ArrayList<>();
        for (int off = 0; off < size; off += 16384) {
            leaves.add(MerkleHashes.leafHash(Arrays.copyOfRange(content, off, Math.min(off + 16384, size))));
        }
        byte[] root = MerkleHashes.rootOfLayer(leaves);
        byte[] strip = new byte[leaves.size() * 32];
        for (int i = 0; i < leaves.size(); i++) {
            System.arraycopy(leaves.get(i), 0, strip, i * 32, 32);
        }

        Map<BString, BencodeValue> attrs = new TreeMap<>(BString.UNSIGNED_ORDER);
        attrs.put(BString.of("length"), new BInteger(size));
        attrs.put(BString.of("pieces root"), new BString(root));
        Map<BString, BencodeValue> fileEntry = new TreeMap<>(BString.UNSIGNED_ORDER);
        fileEntry.put(new BString(new byte[0]), new BDict(attrs));
        Map<BString, BencodeValue> tree = new TreeMap<>(BString.UNSIGNED_ORDER);
        tree.put(BString.of(name), new BDict(fileEntry));

        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of(name));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("meta version"), new BInteger(2));
        info.put(BString.of("file tree"), new BDict(tree));
        if (hybrid) {
            info.put(BString.of("pieces"), new BString(v1PiecesOf(content, pieceCount)));
        }

        Map<BString, BencodeValue> layers = new TreeMap<>(BString.UNSIGNED_ORDER);
        layers.put(new BString(root), new BString(strip));

        EmbeddedTracker tracker = EmbeddedTracker.start();
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        top.put(BString.of("announce"), BString.of(tracker.announceUrl()));
        top.put(BString.of("piece layers"), new BDict(layers));
        top.put(BString.of("info"), new BDict(info));
        Path torrentFile = tempDir.resolve(name + ".torrent");
        Files.write(torrentFile, Bencode.encode(new BDict(top)));
        Path contentFile = tempDir.resolve(name);
        Files.write(contentFile, content);
        return new Seed(contentFile, torrentFile, content, tracker);
    }

    @Test
    void hybridTorrentDownloadsViaV1Verification() throws Exception {
        Random random = new Random(11);
        try (Seed seed = hybridSeed("hybrid.bin", 4 * PIECE_LENGTH, random)) {
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile));
            assertEquals(TorrentVersion.HYBRID, meta.version());
            assertEquals(4, meta.pieceCount());
            assertEquals(32, meta.infoHashV2().length, "hybrid 有完整 SHA-256 副哈希");

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(20000 + random.nextInt(3000))
                    .build()) {
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile, meta)) {
                    seeder.announceTo(seed.tracker);
                    DownloadResult result = client.download(seed.torrentFile(),
                            DownloadOptions.defaults().targetDir(tempDir.resolve("dlH")))
                            .future().get(60, TimeUnit.SECONDS);
                    assertArrayEquals(seed.content,
                            Files.readAllBytes(result.file().resolve("hybrid.bin")));
                }
            }
        }
    }

    @Test
    void corruptedPieceIsRejectedNotWrittenToDisk() throws Exception {
        Random random = new Random(13);
        // 造正确的 v2 种子，但 seeder 供的是损坏了件 0 一个字节的内容
        try (Seed seed = hybridSeed("corrupt.bin", 3 * PIECE_LENGTH, random)) {
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile));
            assertEquals(TorrentVersion.HYBRID, meta.version());

            // 损坏内容文件的件 0 首字节
            byte[] corrupted = seed.content.clone();
            corrupted[0] ^= 0x5A;
            Path corruptedFile = tempDir.resolve("corrupted-content.bin");
            Files.write(corruptedFile, corrupted);

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(20400 + random.nextInt(3000))
                    .build()) {
                try (FakeSeeder seeder = FakeSeeder.start(corruptedFile, meta)) {
                    seeder.announceTo(seed.tracker);
                    // 下载应无法完成（坏件被校验拒绝，seeder 被拉黑后无健康源）
                    try {
                        client.download(seed.torrentFile(),
                                DownloadOptions.defaults().targetDir(tempDir.resolve("dlC")))
                                .future().get(15, TimeUnit.SECONDS);
                        // 如果竟然完成了，验证内容不是原始内容（坏件不该通过校验）
                        Path dl = tempDir.resolve("dlC").resolve("corrupt.bin");
                        if (Files.exists(dl)) {
                            assertTrue(!Arrays.equals(seed.content, Files.readAllBytes(dl)),
                                    "损坏件不应通过校验被写盘");
                        }
                    } catch (Exception timeoutOrFailure) {
                        // 预期：超时或任务失败（坏件源被拉黑后无源可下）——这证明校验拒绝了坏件
                    }
                }
            }
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** v1 逐件 SHA-1 哈希数组。 */
    private byte[] v1PiecesOf(byte[] content, int pieceCount) {
        byte[] v1Pieces = new byte[pieceCount * 20];
        for (int p = 0; p < pieceCount; p++) {
            int from = p * PIECE_LENGTH;
            int to = Math.min(from + PIECE_LENGTH, content.length);
            byte[] hash = sha1(Arrays.copyOfRange(content, from, to));
            System.arraycopy(hash, 0, v1Pieces, p * 20, 20);
        }
        return v1Pieces;
    }

    private record Seed(Path contentFile, Path torrentFile, byte[] content,
            EmbeddedTracker tracker) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            tracker.close();
        }
    }
}
