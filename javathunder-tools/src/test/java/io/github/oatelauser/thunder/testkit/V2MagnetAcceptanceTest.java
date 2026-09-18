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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * v2-only 磁力端到端验收（BEP 52 哈希交换）：btmh 磁力 → MetadataFetcher 拉回 info
 * 字典（无层带，lazy 构造）→ PieceLayerFetcher 经 hash request/hashes 拉层带并按
 * Merkle 证明验证 → 正常下载会话（V2 逐件校验）→ 字节级比对。
 *
 * <p>piece 取 32KiB（pieceLayer=1）、件数 5（非 2 的幂）——同时覆盖填充链约定
 * （pad₁ ≠ 零哈希）与"整层单块请求证明为空"的路径。
 * 端口说明：listenPort 分段基址只是随机抖动起点，并非跨测试类的防撞约定
 * （窄带彼此重叠、且落在它类的宽带 17000–37000 内），勿据此新增"端口分配表"。
 */
class V2MagnetAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 32 * 1024;

    @Test
    void v2OnlyMagnetFetchesLayersViaHashExchangeAndDownloads() throws Exception {
        Random random = new Random(31);
        int size = 5 * PIECE_LENGTH;
        byte[] content = new byte[size];
        random.nextBytes(content);
        List<byte[]> pieces = pieceHashes(content);

        try (V2MagnetSeed seed = v2Seed("v2-magnet.bin", content, pieces)) {
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
            assertEquals(5, meta.pieceCount());

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(22000 + random.nextInt(3000))
                    .build()) {
                try (MetadataSeeder seeder = MetadataSeeder.start(seed.contentFile(), meta,
                        Files.readAllBytes(seed.torrentFile()))) {
                    seeder.announceTo(seed.tracker());
                    DownloadResult result = client.download(seed.magnet(),
                            DownloadOptions.defaults().targetDir(tempDir.resolve("dlV2M")))
                            .future().get(90, TimeUnit.SECONDS);
                    assertArrayEquals(content,
                            Files.readAllBytes(result.file().resolve("v2-magnet.bin")),
                            "v2-only 磁力下载内容应与原始内容字节级一致");
                }
            }
        }
    }

    /** 造种子与 btmh 磁力：.torrent 带层带（供 MetadataSeeder 服务 hash request）。 */
    private V2MagnetSeed v2Seed(String name, byte[] content, List<byte[]> pieces) throws Exception {
        byte[] root = MerkleHashes.rootOfLayer(pieces, 1);
        byte[] strip = new byte[pieces.size() * MerkleHashes.HASH_WIDTH];
        for (int i = 0; i < pieces.size(); i++) {
            System.arraycopy(pieces.get(i), 0, strip, i * MerkleHashes.HASH_WIDTH,
                    MerkleHashes.HASH_WIDTH);
        }

        EmbeddedTracker tracker = EmbeddedTracker.start();
        Map<BString, BencodeValue> info = v2InfoDict(name, content.length, root);
        Map<BString, BencodeValue> layers = new TreeMap<>(BString.UNSIGNED_ORDER);
        layers.put(new BString(root), new BString(strip));
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        top.put(BString.of("announce"), BString.of(tracker.announceUrl()));
        top.put(BString.of("piece layers"), new BDict(layers));
        top.put(BString.of("info"), new BDict(info));
        byte[] torrentBytes = Bencode.encode(new BDict(top));

        Path torrentFile = tempDir.resolve(name + ".torrent");
        Files.write(torrentFile, torrentBytes);
        Path contentFile = tempDir.resolve(name);
        Files.write(contentFile, content);

        // btmh 的 64 hex = info 字典原始字节的完整 SHA-256
        byte[] infoRaw = TorrentParser.extractInfoDict(torrentBytes);
        String hex = HexFormat.of().formatHex(sha256(infoRaw));
        MagnetUri magnet = MagnetUri.parse("magnet:?xt=urn:btmh:1220" + hex
                + "&tr=" + tracker.announceUrl());
        assertNotNull(magnet.infoHashV2());
        return new V2MagnetSeed(contentFile, torrentFile, magnet, tracker);
    }

    /** v2-only info 字典（file tree，无 v1 pieces）。 */
    private Map<BString, BencodeValue> v2InfoDict(String name, int size, byte[] root) {
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
        return info;
    }

    /** 逐件哈希：每件 2 个 16KiB 块叶子折叠（32KiB piece → pieceLayer=1）。 */
    private List<byte[]> pieceHashes(byte[] content) {
        List<byte[]> pieces = new ArrayList<>();
        for (int off = 0; off < content.length; off += PIECE_LENGTH) {
            List<byte[]> leaves = new ArrayList<>();
            for (int b = 0; b < PIECE_LENGTH; b += 16384) {
                leaves.add(MerkleHashes.leafHash(Arrays.copyOfRange(content,
                        off + b, off + b + 16384)));
            }
            pieces.add(MerkleHashes.rootOfLayer(leaves));
        }
        return pieces;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record V2MagnetSeed(Path contentFile, Path torrentFile, MagnetUri magnet,
            EmbeddedTracker tracker) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            tracker.close();
        }
    }
}
