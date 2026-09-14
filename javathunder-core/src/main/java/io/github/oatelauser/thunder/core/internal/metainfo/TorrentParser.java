package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * v1 .torrent 解析器（BEP 3 / 12 / 27）。
 *
 * <p>info-hash 必须对 info 字典的<b>原始字节区间</b>计算——本解析器在扫描顶层字典时记录
 * info 值的字节边界，禁止"解码后重编码再哈希"（规范形可能与原始字节不一致）。
 */
public final class TorrentParser {

    private TorrentParser() {
    }

    public static TorrentMetadata parse(byte[] bytes) {
        if (bytes.length > Bencode.MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("torrent exceeds input limit: " + bytes.length);
        }
        Scanned scanned = scan(bytes);
        return build(scanned);
    }

    private record Scanned(
        @org.jspecify.annotations.Nullable String announce,
        List<List<String>> announceList,
        @org.jspecify.annotations.Nullable String comment,
        @org.jspecify.annotations.Nullable String createdBy,
        @org.jspecify.annotations.Nullable Long creationDateSec,
        BDict info,
        byte[] infoRawBytes) {
    }

    private static Scanned scan(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (!buf.hasRemaining() || (buf.get() & 0xFF) != 'd') {
            throw new IllegalArgumentException("top level must be a bencoded dict");
        }
        String announce = null;
        List<List<String>> announceList = List.of();
        String comment = null;
        String createdBy = null;
        Long creationDateSec = null;
        BDict info = null;
        int infoStart = -1;
        int infoEnd = -1;
        while (true) {
            if (!buf.hasRemaining()) {
                throw new IllegalArgumentException("top-level dict not terminated");
            }
            int tag = buf.get(buf.position()) & 0xFF;
            if (tag == 'e') {
                buf.get();
                break;
            }
            if (tag < '0' || tag > '9') {
                throw new IllegalArgumentException("top-level key must be a byte string");
            }
            BencodeValue keyValue = Bencode.decodeValue(buf);
            String key = ((BString) keyValue).text();
            if ("info".equals(key)) {
                infoStart = buf.position();
                BencodeValue value = Bencode.decodeValue(buf);
                infoEnd = buf.position();
                if (!(value instanceof BDict infoDict)) {
                    throw new IllegalArgumentException("info must be a dict");
                }
                info = infoDict;
            } else {
                BencodeValue value = Bencode.decodeValue(buf);
                switch (key) {
                    case "announce" -> announce = asString(value, "announce");
                    case "announce-list" -> announceList = asTiers(value);
                    case "comment" -> comment = asString(value, "comment");
                    case "created by" -> createdBy = asString(value, "created by");
                    case "creation date" -> creationDateSec = asInteger(value, "creation date").value();
                    default -> {
                    }
                }
            }
        }
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException("trailing data after top-level dict");
        }
        if (info == null || infoStart < 0) {
            throw new IllegalArgumentException("missing info dict");
        }
        byte[] infoRaw = new byte[infoEnd - infoStart];
        buf.position(infoStart);
        buf.get(infoRaw);
        return new Scanned(announce, announceList, comment, createdBy, creationDateSec, info, infoRaw);
    }

    private static TorrentMetadata build(Scanned s) {
        if (s.announce() == null && s.announceList().isEmpty()) {
            throw new IllegalArgumentException("no tracker in torrent (announce/announce-list); "
                + "trackerless download arrives with DHT in phase 2");
        }
        if (s.info().value().containsKey(BString.of("files"))) {
            throw new IllegalArgumentException("multi-file torrents are not supported until phase 2");
        }
        String name = requireString(s.info(), "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("info.name must not be empty");
        }
        long length = requireInteger(s.info(), "length").value();
        if (length <= 0) {
            throw new IllegalArgumentException("info.length must be positive in phase 1");
        }
        long pieceLength = requireInteger(s.info(), "piece length").value();
        if (pieceLength <= 0) {
            throw new IllegalArgumentException("info.'piece length' must be positive");
        }
        BString pieces = requireStringRaw(s.info(), "pieces");
        if (pieces.value().length == 0 || pieces.value().length % 20 != 0) {
            throw new IllegalArgumentException("info.pieces must be a non-empty multiple of 20 bytes");
        }
        long expectedPieces = (length + pieceLength - 1) / pieceLength;
        if (pieces.value().length / 20 != expectedPieces) {
            throw new IllegalArgumentException("piece count mismatch: pieces has "
                + (pieces.value().length / 20) + " hashes, length/piece-length implies " + expectedPieces);
        }
        boolean privateFlag = s.info().value().containsKey(BString.of("private"))
            && asInteger(s.info().get("private"), "private").value() == 1;

        return new TorrentMetadata(sha1(s.infoRawBytes()), s.announce(), s.announceList(),
            s.comment(), s.createdBy(), s.creationDateSec(),
            name, length, pieceLength, pieces.value(), privateFlag);
    }

    private static String requireString(BDict dict, String key) {
        return asString(require(dict, key), key);
    }

    private static BString requireStringRaw(BDict dict, String key) {
        BencodeValue value = require(dict, key);
        if (!(value instanceof BString s)) {
            throw new IllegalArgumentException(key + " must be a byte string");
        }
        return s;
    }

    private static BInteger requireInteger(BDict dict, String key) {
        return asInteger(require(dict, key), key);
    }

    private static BencodeValue require(BDict dict, String key) {
        BencodeValue value = dict.value().get(BString.of(key));
        if (value == null) {
            throw new IllegalArgumentException("missing required info field: " + key);
        }
        return value;
    }

    private static String asString(BencodeValue value, String field) {
        if (!(value instanceof BString s)) {
            throw new IllegalArgumentException(field + " must be a byte string");
        }
        return s.text();
    }

    private static BInteger asInteger(BencodeValue value, String field) {
        if (!(value instanceof BInteger i)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return i;
    }

    private static List<List<String>> asTiers(BencodeValue value) {
        if (!(value instanceof BList tiers)) {
            throw new IllegalArgumentException("announce-list must be a list of lists");
        }
        List<List<String>> result = new ArrayList<>();
        for (BencodeValue tierValue : tiers.value()) {
            if (!(tierValue instanceof BList tier)) {
                throw new IllegalArgumentException("announce-list tier must be a list");
            }
            List<String> urls = new ArrayList<>();
            for (BencodeValue url : tier.value()) {
                urls.add(asString(url, "announce-list entry"));
            }
            result.add(List.copyOf(urls));
        }
        return List.copyOf(result);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-1", e);
        }
    }
}
