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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v2 种子端到端验收（BEP 52 S3）：手造 v2 种子（file tree + piece layers + Merkle 根），
 * FakeSeeder 按线协议供块，引擎走完整下载管线（V2PieceVerifier 校验 + MultiFileStorage
 * 落位），最终字节级比对。纯 v2（无 v1 pieces 字段）。
 */
class V2TorrentAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int PIECE_LENGTH = 16 * 1024;

    /** 造 v2 种子：单文件 model.bin(49152B = 3 件) + 层带（叶子层即 piece=16KiB）。 */
    private V2Seed v2Seed(String name, int size, Random random) throws IOException {
        byte[] content = new byte[size];
        random.nextBytes(content);

        List<byte[]> leaves = new ArrayList<>();
        for (int off = 0; off < size; off += 16384) {
            leaves.add(MerkleHashes.leafHash(Arrays.copyOfRange(content, off,
                    Math.min(off + 16384, size))));
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
        return new V2Seed(contentFile, torrentFile, content, tracker);
    }

    @Test
    void v2OnlyTorrentDownloadsAndVerifies() throws Exception {
        Random random = new Random(42);
        try (V2Seed seed = v2Seed("model-v2.bin", 3 * PIECE_LENGTH, random)) {
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
            assertEquals(TorrentVersion.V2, meta.version());
            assertEquals(3, meta.pieceCount());

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(19000 + random.nextInt(3000))
                    .build()) {
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile, meta)) {
                    seeder.announceTo(seed.tracker);
                    DownloadResult result = client.download(seed.torrentFile(),
                            DownloadOptions.defaults().targetDir(tempDir.resolve("dl")))
                            .future().get(60, TimeUnit.SECONDS);
                    // v2 单文件走 MultiFileStorage（file tree 有一项）：result.file() 是根目录
                    assertArrayEquals(seed.content,
                            Files.readAllBytes(result.file().resolve("model-v2.bin")),
                            "下载内容应与种子内容字节级一致");
                }
            }
        }
    }

    @Test
    void v2TorrentWithPartialLastPieceDownloads() throws Exception {
        Random random = new Random(7);
        // 50000B = 4 件（末件 848B < 16KiB，截断场景）
        try (V2Seed seed = v2Seed("partial-v2.bin", 50000, random)) {
            TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(seed.torrentFile()));
            assertEquals(TorrentVersion.V2, meta.version());
            assertEquals(4, meta.pieceCount(), "50000B / 16KiB → 4 件（末件 848B）");

            try (TorrentClient client = TorrentClient.builder()
                    .transport(Transports.select())
                    .listenPort(19400 + random.nextInt(3000))
                    .build()) {
                try (FakeSeeder seeder = FakeSeeder.start(seed.contentFile, meta)) {
                    seeder.announceTo(seed.tracker);
                    DownloadResult result = client.download(seed.torrentFile(),
                            DownloadOptions.defaults().targetDir(tempDir.resolve("dl2")))
                            .future().get(60, TimeUnit.SECONDS);
                    assertArrayEquals(seed.content,
                            Files.readAllBytes(result.file().resolve("partial-v2.bin")));
                }
            }
        }
    }

    private record V2Seed(Path contentFile, Path torrentFile, byte[] content,
            EmbeddedTracker tracker) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            tracker.close();
        }
    }
}
