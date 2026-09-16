package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试用种子由 Bencode 编码器构造；期望 info-hash 由测试自行对 info 字典字节做 SHA-1，
 * 与解析器内部实现相互独立。
 */
class TorrentParserTest {

    /** 640 字节内容 / 256B piece → 3 个 piece（末块 128B）。 */
    private static final long CONTENT_LENGTH = 640;
    private static final long PIECE_LENGTH = 256;

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BDict infoDict() {
        byte[] pieces = new byte[20 * 3];
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = (byte) i;
        }
        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of("test.bin"));
        info.put(BString.of("piece length"), new BInteger(PIECE_LENGTH));
        info.put(BString.of("length"), new BInteger(CONTENT_LENGTH));
        info.put(BString.of("pieces"), new BString(pieces));
        return new BDict(info);
    }

    private static byte[] torrent(BDict info, Map<BString, BencodeValue> extraTopLevel) {
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        top.put(BString.of("announce"), BString.of("http://tracker.example/announce"));
        top.putAll(extraTopLevel);
        top.put(BString.of("info"), info);
        return Bencode.encode(new BDict(top));
    }

    @Nested
    class ValidTorrents {

        @Test
        void parsesCoreFieldsAndComputesInfoHashFromRawBytes() {
            BDict info = infoDict();
            TorrentMetadata meta = TorrentParser.parse(torrent(info, Map.of()));

            assertEquals("http://tracker.example/announce", meta.announce());
            assertEquals("test.bin", meta.name());
            assertEquals(CONTENT_LENGTH, meta.length());
            assertEquals(PIECE_LENGTH, meta.pieceLength());
            assertEquals(3, meta.pieceCount());
            assertArrayEquals(sha1(Bencode.encode(info)), meta.infoHash());
            assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19},
                meta.pieceHash(0));
            assertEquals(false, meta.privateFlag());
        }

        @Test
        void infoHashCoversExactlyTheInfoByteRangeEvenWithUnsortedTopLevelKeys() {
            // 手工拼装：announce 排在 info 之后（非字典序），info-hash 仍须等于 info 原始字节的 SHA-1
            byte[] infoBytes = Bencode.encode(infoDict());
            ByteBuffer buf = ByteBuffer.allocate(1024);
            buf.put((byte) 'd');
            buf.put(Bencode.encode(BString.of("zz-comment")));
            buf.put(Bencode.encode(BString.of("padding to disorder keys")));
            buf.put(Bencode.encode(BString.of("info")));
            int infoStart = buf.position();
            buf.put(infoBytes);
            int infoEnd = buf.position();
            buf.put(Bencode.encode(BString.of("announce")));
            buf.put(Bencode.encode(BString.of("http://tracker.example/announce")));
            buf.put((byte) 'e');
            byte[] raw = new byte[buf.position()];
            buf.flip();
            buf.get(raw);

            assertArrayEquals(sha1(Arrays.copyOfRange(raw, infoStart, infoEnd)),
                TorrentParser.parse(raw).infoHash());
        }

        @Test
        void parsesAnnounceListTiersAndPrivateFlag() {
            Map<BString, BencodeValue> extras = new TreeMap<>(BString.UNSIGNED_ORDER);
            extras.put(BString.of("announce-list"), new BList(List.of(
                new BList(List.of(BString.of("http://t1/announce"), BString.of("http://t2/announce"))),
                new BList(List.of(BString.of("udp://t3/announce"))))));
            extras.put(BString.of("comment"), BString.of("a comment"));
            extras.put(BString.of("created by"), BString.of("mktorrent"));
            extras.put(BString.of("creation date"), new BInteger(1_700_000_000L));

            Map<BString, BencodeValue> infoExtras = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoExtras.putAll(Map.ofEntries(Map.entry(BString.of("name"), BString.of("test.bin")),
                Map.entry(BString.of("piece length"), new BInteger(PIECE_LENGTH)),
                Map.entry(BString.of("length"), new BInteger(CONTENT_LENGTH))));
            byte[] pieces = new byte[60];
            infoExtras.put(BString.of("pieces"), new BString(pieces));
            infoExtras.put(BString.of("private"), new BInteger(1));
            BDict info = new BDict(infoExtras);

            TorrentMetadata meta = TorrentParser.parse(torrent(info, extras));

            assertEquals(List.of(
                List.of("http://t1/announce", "http://t2/announce"),
                List.of("udp://t3/announce")), meta.announceList());
            assertEquals(true, meta.privateFlag());
            assertEquals("a comment", meta.comment());
            assertEquals("mktorrent", meta.createdBy());
            assertEquals(1_700_000_000L, meta.creationDateSec());
        }

        @Test
        void singlePieceTorrent() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("one.bin"));
            infoFields.put(BString.of("piece length"), new BInteger(262_144));
            infoFields.put(BString.of("length"), new BInteger(100));
            infoFields.put(BString.of("pieces"), new BString(new byte[20]));
            TorrentMetadata meta = TorrentParser.parse(torrent(new BDict(infoFields), Map.of()));
            assertEquals(1, meta.pieceCount());
            assertNull(meta.comment());
        }
    }

    @Nested
    class InvalidTorrents {

        @Test
        void missingInfoDict() {
            assertThrows(IllegalArgumentException.class,
                () -> TorrentParser.parse(Bencode.encode(new BDict(Map.of(
                    BString.of("announce"), BString.of("http://t/"))))));
        }

        @Test
        void missingTracker() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("x"));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("length"), new BInteger(256));
            infoFields.put(BString.of("pieces"), new BString(new byte[20]));
            byte[] bytes = Bencode.encode(new BDict(Map.of(BString.of("info"), new BDict(infoFields))));
            assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes));
        }

        @Test
        void pieceCountMismatch() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("x"));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("length"), new BInteger(1000));
            infoFields.put(BString.of("pieces"), new BString(new byte[20])); // 应为 4 块
            byte[] bytes = torrent(new BDict(infoFields), Map.of());
            assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes));
        }

        @Test
        void piecesNotMultipleOfTwenty() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("x"));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("length"), new BInteger(256));
            infoFields.put(BString.of("pieces"), new BString(new byte[13]));
            byte[] bytes = torrent(new BDict(infoFields), Map.of());
            assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes));
        }

        @Test
        void emptyFilesListRejected() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("dir"));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("pieces"), new BString(new byte[20]));
            infoFields.put(BString.of("files"), new BList(List.of()));
            byte[] bytes = torrent(new BDict(infoFields), Map.of());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TorrentParser.parse(bytes));
            assertEquals("info.files must be a non-empty list", e.getMessage());
        }

        @Test
        void parsesMultiFileTorrentWithOffsets() {
            // 两个文件：weights.bin 640B + config.json 100B → 拼接流 740B / 256B 件 → 3 件
            Map<BString, BencodeValue> file1 = new TreeMap<>(BString.UNSIGNED_ORDER);
            file1.put(BString.of("length"), new BInteger(640));
            file1.put(BString.of("path"), new BList(List.of(BString.of("weights.bin"))));
            Map<BString, BencodeValue> file2 = new TreeMap<>(BString.UNSIGNED_ORDER);
            file2.put(BString.of("length"), new BInteger(100));
            file2.put(BString.of("path"), new BList(List.of(
                BString.of("nested"), BString.of("config.json"))));
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("model-x"));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("length"), new BInteger(740));
            infoFields.put(BString.of("pieces"), new BString(new byte[60])); // 3 块
            infoFields.put(BString.of("files"), new BList(List.of(new BDict(file1), new BDict(file2))));
            // 去掉单文件 length 键的多件构造：直接手拼 info
            infoFields.remove(BString.of("length"));

            TorrentMetadata meta = TorrentParser.parse(torrent(new BDict(infoFields), Map.of()));

            assertEquals(true, meta.multiFile());
            assertEquals(740, meta.length());
            assertEquals(2, meta.files().size());
            assertEquals(List.of("weights.bin"), meta.files().get(0).path());
            assertEquals(0, meta.files().get(0).offset());
            assertEquals(640, meta.files().get(0).length());
            assertEquals(List.of("nested", "config.json"), meta.files().get(1).path());
            assertEquals(640, meta.files().get(1).offset());
        }

        @Test
        void rejectsPathTraversalComponents() {
            for (String evil : new String[]{"..", "", "a/b", "a\\b", "C:evil", "CON", "com1.txt",
                "nul.bin", "bad\u0001name"}) {
                Map<BString, BencodeValue> file = new TreeMap<>(BString.UNSIGNED_ORDER);
                file.put(BString.of("length"), new BInteger(10));
                file.put(BString.of("path"), new BList(List.of(BString.of(evil))));
                Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
                infoFields.put(BString.of("name"), BString.of("d"));
                infoFields.put(BString.of("piece length"), new BInteger(256));
                infoFields.put(BString.of("length"), new BInteger(10));
                infoFields.put(BString.of("pieces"), new BString(new byte[20]));
                infoFields.put(BString.of("files"), new BList(List.of(new BDict(file))));
                byte[] bytes = torrent(new BDict(infoFields), Map.of());
                assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes),
                    "path component should be rejected: " + evil);
            }
        }

        @Test
        void nonPositivePieceLength() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("x"));
            infoFields.put(BString.of("piece length"), new BInteger(0));
            infoFields.put(BString.of("length"), new BInteger(256));
            infoFields.put(BString.of("pieces"), new BString(new byte[20]));
            byte[] bytes = torrent(new BDict(infoFields), Map.of());
            assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes));
        }

        @Test
        void emptyName() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of(""));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("length"), new BInteger(256));
            infoFields.put(BString.of("pieces"), new BString(new byte[20]));
            byte[] bytes = torrent(new BDict(infoFields), Map.of());
            assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes));
        }
    }

    /** url-list（BEP 19）：双形态归一、缺省为空、非法形态拒绝、不参与 info-hash。 */
    @Nested
    class WebSeedList {

        @Test
        void singleStringFormNormalizesToOneElementList() {
            Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
            top.put(BString.of("url-list"), BString.of("http://mirror.example/file.iso"));
            TorrentMetadata meta = TorrentParser.parse(torrent(infoDict(), top));
            assertEquals(List.of("http://mirror.example/file.iso"), meta.webSeeds());
        }

        @Test
        void listOfStringsFormIsKeptInOrder() {
            Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
            top.put(BString.of("url-list"), new BList(List.of(
                    BString.of("http://a.example/f"), BString.of("http://b.example/f"))));
            TorrentMetadata meta = TorrentParser.parse(torrent(infoDict(), top));
            assertEquals(List.of("http://a.example/f", "http://b.example/f"), meta.webSeeds());
        }

        @Test
        void absentFieldYieldsEmptyList() {
            assertEquals(List.of(), TorrentParser.parse(torrent(infoDict(), Map.of())).webSeeds());
        }

        @Test
        void nonStringFormIsRejected() {
            Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
            top.put(BString.of("url-list"), new BInteger(3));
            byte[] bytes = torrent(infoDict(), top);
            assertThrows(IllegalArgumentException.class, () -> TorrentParser.parse(bytes));
        }

        @Test
        void urlListDoesNotAffectInfoHash() {
            byte[] plain = TorrentParser.parse(torrent(infoDict(), Map.of())).infoHash();
            Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
            top.put(BString.of("url-list"), BString.of("http://mirror.example/file.iso"));
            byte[] withUrlList = TorrentParser.parse(torrent(infoDict(), top)).infoHash();
            assertArrayEquals(plain, withUrlList);
        }

        @Test
        void trackerlessUrlListOnlyTorrentIsAccepted() {
            // 纯 WebSeed 种子（无 announce/announce-list，仅 url-list）应可解析
            Map<BString, BencodeValue> raw = new TreeMap<>(BString.UNSIGNED_ORDER);
            raw.put(BString.of("url-list"), BString.of("http://mirror.example/file.iso"));
            raw.put(BString.of("info"), infoDict());
            TorrentMetadata meta = TorrentParser.parse(Bencode.encode(new BDict(raw)));
            assertEquals(List.of("http://mirror.example/file.iso"), meta.webSeeds());
            assertTrue(meta.trackerTiers().isEmpty(), "url-list-only torrent has no tracker tiers");
        }
    }
}
