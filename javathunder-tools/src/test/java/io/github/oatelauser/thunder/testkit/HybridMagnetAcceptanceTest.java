package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.MagnetUri;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * hybrid 磁力端到端验收（BEP 52）：磁力（v1 btih 哈希）→ MetadataFetcher（ut_metadata
 * 拉到含 file tree 的 info 字典）→ hybrid 元数据（无 piece layers，v1 面校验）→
 * FakeSeeder 供块 → 下载完成 → 字节级比对。
 */
class HybridMagnetAcceptanceTest {

    @TempDir
    Path tempDir;
    private static final int PIECE_LENGTH = 16 * 1024;

    @Test
    void hybridMagnetDownloadsViaV1FaceWithoutPieceLayers() throws Exception {
        Random random = new Random(21);
        int size = 3 * PIECE_LENGTH;
        byte[] content = new byte[size];
        random.nextBytes(content);
        byte[] root = merkleRoot(content);
        Map<BString, BencodeValue> info = hybridInfoDict(size, v1PiecesOf(content), root);

        try (EmbeddedTracker tracker = EmbeddedTracker.start()) {
            // .torrent 供 MetadataSeeder 服务磁力元数据（BEP 9 传 info 字典）
            Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
            top.put(BString.of("announce"), BString.of(tracker.announceUrl()));
            top.put(BString.of("info"), new BDict(info));
            Path torrentFile = tempDir.resolve("hm.torrent");
            Files.write(torrentFile, Bencode.encode(new BDict(top)));
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(torrentFile));
            assertEquals(TorrentVersion.HYBRID, meta.version());
            Path contentFile = tempDir.resolve("hm-content.bin");
            Files.write(contentFile, content);

            // 磁力用 v1 info-hash（hybrid 双 xt 中 btih 侧——生态主流用法）
            String hex = HexFormat.of().formatHex(meta.infoHash());
            MagnetUri magnet = MagnetUri.parse("magnet:?xt=urn:btih:" + hex
                    + "&tr=" + tracker.announceUrl());

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(21000 + random.nextInt(3000))
                    .build()) {
                try (MetadataSeeder seeder = MetadataSeeder.start(contentFile, meta,
                        Files.readAllBytes(torrentFile))) {
                    seeder.announceTo(tracker);
                    DownloadResult result = client.download(magnet,
                            DownloadOptions.defaults().targetDir(tempDir.resolve("dlM")))
                            .future().get(90, TimeUnit.SECONDS);
                    assertArrayEquals(content,
                            Files.readAllBytes(result.file().resolve("hybrid-magnet.bin")),
                            "hybrid 磁力下载内容应与原始内容字节级一致");
                }
            }
        }
    }

    /** v1 逐件 SHA-1 哈希数组。 */
    private byte[] v1PiecesOf(byte[] content) {
        int pieceCount = (content.length + PIECE_LENGTH - 1) / PIECE_LENGTH;
        byte[] v1Pieces = new byte[pieceCount * 20];
        for (int p = 0; p < pieceCount; p++) {
            byte[] hash = sha1(Arrays.copyOfRange(content, p * PIECE_LENGTH,
                    Math.min((p + 1) * PIECE_LENGTH, content.length)));
            System.arraycopy(hash, 0, v1Pieces, p * 20, 20);
        }
        return v1Pieces;
    }

    /** 整份内容的 Merkle 根（16KiB 叶子 → 折叠）。 */
    private byte[] merkleRoot(byte[] content) {
        List<byte[]> leaves = new ArrayList<>();
        for (int off = 0; off < content.length; off += 16384) {
            leaves.add(MerkleHashes.leafHash(Arrays.copyOfRange(content, off,
                    Math.min(off + 16384, content.length))));
        }
        return MerkleHashes.rootOfLayer(leaves);
    }

    /** hybrid info 字典（含 v1 pieces 与 v2 file tree——磁力元数据形态）。 */
    private Map<BString, BencodeValue> hybridInfoDict(int size, byte[] v1Pieces, byte[] root) {
        Map<BString, BencodeValue> attrs = new TreeMap<>(BString.UNSIGNED_ORDER);
        attrs.put(BString.of("length"), new BInteger(size));
        attrs.put(BString.of("pieces root"), new BString(root));
        Map<BString, BencodeValue> fileEntry = new TreeMap<>(BString.UNSIGNED_ORDER);
        fileEntry.put(new BString(new byte[0]), new BDict(attrs));
        Map<BString, BencodeValue> tree = new TreeMap<>(BString.UNSIGNED_ORDER);
        tree.put(BString.of("hybrid-magnet.bin"), new BDict(fileEntry));

        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of("hybrid-magnet.bin"));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("meta version"), new BInteger(2));
        info.put(BString.of("file tree"), new BDict(tree));
        info.put(BString.of("pieces"), new BString(v1Pieces));
        return info;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
