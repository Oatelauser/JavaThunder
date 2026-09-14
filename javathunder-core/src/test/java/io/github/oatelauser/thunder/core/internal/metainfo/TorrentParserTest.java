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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

            assertArrayEquals(sha1(java.util.Arrays.copyOfRange(raw, infoStart, infoEnd)),
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
        void multiFileTorrentRejectedInPhaseOne() {
            Map<BString, BencodeValue> infoFields = new TreeMap<>(BString.UNSIGNED_ORDER);
            infoFields.put(BString.of("name"), BString.of("dir"));
            infoFields.put(BString.of("piece length"), new BInteger(256));
            infoFields.put(BString.of("files"), new BList(List.of()));
            byte[] bytes = torrent(new BDict(infoFields), Map.of());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TorrentParser.parse(bytes));
            assertEquals("multi-file torrents are not supported until phase 2", e.getMessage());
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
}
