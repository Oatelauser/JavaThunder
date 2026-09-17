package io.github.oatelauser.thunder.core.internal.metainfo;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * v1 .torrent 解析器（BEP 3 / 12 / 19 / 27）。
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

    /**
     * 从 .torrent 原始字节切出 info 字典的原始字节区间（ut_metadata 供元数据用；纯定位，不做 announce 等业务校验）。
     */
    public static byte[] extractInfoDict(byte[] bytes) {
        if (bytes.length > Bencode.MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("torrent exceeds input limit: " + bytes.length);
        }
        return scan(bytes).infoRawBytes();
    }

    private record Scanned(
            @Nullable String announce,
            List<List<String>> announceList,
            @Nullable String comment,
            @Nullable String createdBy,
            @Nullable Long creationDateSec,
            BDict info,
            byte[] infoRawBytes,
            List<String> webSeeds,
            @Nullable BDict pieceLayers) {
    }

    private static Scanned scan(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (!buf.hasRemaining() || (buf.get() & 0xFF) != 'd') {
            throw new IllegalArgumentException("top level must be a bencoded dict");
        }
        TopLevel top = scanTopLevel(buf);
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException("trailing data after top-level dict");
        }
        if (top.info == null || top.infoStart < 0) {
            throw new IllegalArgumentException("missing info dict");
        }
        return new Scanned(top.announce, top.announceList, top.comment, top.createdBy,
                top.creationDateSec, top.info, sliceInfoRaw(buf, top), top.webSeeds, top.pieceLayers);
    }

    /**
     * 逐条扫描顶层字典直到 'e'；info 值记录原始字节边界（info-hash 语义，见类注释）。
     */
    private static TopLevel scanTopLevel(ByteBuffer buf) {
        TopLevel top = new TopLevel();
        while (true) {
            if (!buf.hasRemaining()) {
                throw new IllegalArgumentException("top-level dict not terminated");
            }
            int tag = buf.get(buf.position()) & 0xFF;
            if (tag == 'e') {
                buf.get();
                return top;
            }
            if (tag < '0' || tag > '9') {
                throw new IllegalArgumentException("top-level key must be a byte string");
            }
            String key = ((BString) Bencode.decodeValue(buf)).text();
            if ("info".equals(key)) {
                readInfo(buf, top);
            } else {
                readTopLevelField(key, Bencode.decodeValue(buf), top);
            }
        }
    }

    /**
     * 解析 info 值并记录其原始字节区间 [infoStart, infoEnd)。
     */
    private static void readInfo(ByteBuffer buf, TopLevel top) {
        top.infoStart = buf.position();
        BencodeValue value = Bencode.decodeValue(buf);
        top.infoEnd = buf.position();
        if (!(value instanceof BDict infoDict)) {
            throw new IllegalArgumentException("info must be a dict");
        }
        top.info = infoDict;
    }

    /**
     * 顶层已知字段分发；未知键静默跳过（前向兼容未知扩展）。
     */
    private static void readTopLevelField(String key, BencodeValue value, TopLevel top) {
        switch (key) {
            case "announce" -> top.announce = asString(value, "announce");
            case "announce-list" -> top.announceList = asTiers(value);
            case "url-list" -> top.webSeeds = asUrlList(value);
            case "piece layers" -> top.pieceLayers = asPieceLayers(value);
            case "comment" -> top.comment = asString(value, "comment");
            case "created by" -> top.createdBy = asString(value, "created by");
            case "creation date" -> top.creationDateSec = asInteger(value, "creation date").value();
            default -> {
            }
        }
    }

    /**
     * 回退 position 从原始字节切出 info 字典区间（纯读取，不影响已完成的扫描）。
     */
    private static byte[] sliceInfoRaw(ByteBuffer buf, TopLevel top) {
        byte[] infoRaw = new byte[top.infoEnd - top.infoStart];
        buf.position(top.infoStart);
        buf.get(infoRaw);
        return infoRaw;
    }

    /**
     * 顶层字典扫描的累积结果：announce 等已知字段 + info 值与其字节边界。
     */
    private static final class TopLevel {
        @Nullable String announce;
        List<List<String>> announceList = List.of();
        /** WebSeed 兜底源（BEP 19 顶层 url-list，不参与 info-hash）。 */
        List<String> webSeeds = List.of();
        /** v2 piece layers（BEP 52 顶层字段，info 字典之外；root → 层哈希带）。 */
        @Nullable BDict pieceLayers;
        @Nullable String comment;
        @Nullable String createdBy;
        @Nullable Long creationDateSec;
        @Nullable BDict info;
        int infoStart = -1;
        int infoEnd = -1;
    }

    /**
     * 磁力路径（B1）：对已校验的 info 字典做与 .torrent 相同的字段校验并构造元数据。
     */
    public static TorrentMetadata buildFromInfoDict(BDict info, byte[] infoHash, List<String> trackers) {
        List<List<String>> tiers = trackers.isEmpty()
                ? List.of()
                : List.of(List.copyOf(trackers));
        Scanned scanned = new Scanned(
                trackers.isEmpty() ? null : trackers.get(0),
                tiers,
                null, null, null, info, new byte[0], List.of(), null);
        // 直接复用 build：Scanned.infoRawBytes 仅用于 info-hash（这里已外部校验传入）
        return buildWithHash(scanned, infoHash);
    }

    private static TorrentMetadata buildWithHash(Scanned s, byte[] infoHash) {
        TorrentMetadata meta = build(s);
        return new TorrentMetadata(infoHash, meta.announce(), meta.announceList(), meta.comment(),
                meta.createdBy(), meta.creationDateSec(), meta.name(), meta.length(), meta.pieceLength(),
                meta.pieces(), meta.privateFlag(), meta.files(), meta.webSeeds());
    }

    private static TorrentMetadata build(Scanned s) {
        if (s.info().value().containsKey(BString.of("file tree"))) {
            return buildV2(s);
        }
        if (s.announce() == null && s.announceList().isEmpty() && s.webSeeds().isEmpty()) {
            throw new IllegalArgumentException("no tracker and no url-list in torrent; "
                    + "trackerless download requires DHT (inject via peerDiscovery)");
        }
        String name = requireString(s.info(), "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("info.name must not be empty");
        }
        long pieceLength = requireInteger(s.info(), "piece length").value();
        if (pieceLength <= 0) {
            throw new IllegalArgumentException("info.'piece length' must be positive");
        }
        BString pieces = requireStringRaw(s.info(), "pieces");
        if (pieces.value().length == 0 || pieces.value().length % 20 != 0) {
            throw new IllegalArgumentException("info.pieces must be a non-empty multiple of 20 bytes");
        }

        long length;
        List<TorrentMetadata.TorrentFile> files;
        if (s.info().value().containsKey(BString.of("files"))) {
            files = parseFiles(s.info());
            length = files.stream().mapToLong(TorrentMetadata.TorrentFile::length).sum();
            if (length <= 0) {
                throw new IllegalArgumentException("multi-file torrent must have positive total length");
            }
        } else {
            length = requireInteger(s.info(), "length").value();
            if (length <= 0) {
                throw new IllegalArgumentException("info.length must be positive");
            }
            files = List.of();
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
                name, length, pieceLength, pieces.value(), privateFlag, files, s.webSeeds());
    }

    // ---------------------------------------------------------------- v2 / hybrid（BEP 52）

    /**
     * v2/hybrid 解析：file tree 为布局权威（含 BEP 47 填充文件的单一拼接流，v1 视图
     * 与 v2 视图共享）；双 info-hash 对同一份原始 info 字节各算一次（SHA-1 / SHA-256），
     * 主哈希恒 20 字节（v2-only 取 SHA-256 截断前 20 字节，满足 DHT/线协议的 v1 宽度）。
     */
    private static TorrentMetadata buildV2(Scanned s) {
        if (s.announce() == null && s.announceList().isEmpty() && s.webSeeds().isEmpty()) {
            throw new IllegalArgumentException("no tracker and no url-list in torrent; "
                    + "trackerless download requires DHT (inject via peerDiscovery)");
        }
        String name = requireString(s.info(), "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("info.name must not be empty");
        }
        BencodeValue metaVersion = s.info().get("meta version");
        if (!(metaVersion == null || metaVersion instanceof BInteger mv && mv.value() == 2)) {
            throw new IllegalArgumentException("meta version must be 2 when present");
        }
        long pieceLength = requireInteger(s.info(), "piece length").value();
        if (pieceLength < 16 * 1024 || Long.bitCount(pieceLength) != 1) {
            throw new IllegalArgumentException("v2 piece length must be a power of two >= 16KiB");
        }

        List<TorrentMetadata.TorrentFile> walked = new ArrayList<>();
        walkFileTree((BDict) require(s.info(), "file tree"), List.of(), walked);
        if (walked.isEmpty()) {
            throw new IllegalArgumentException("file tree must contain at least one file");
        }
        List<TorrentMetadata.TorrentFile> files = assignOffsets(walked, pieceLength);
        validateLayers(files, pieceLength, s.pieceLayers());
        long length = files.stream().mapToLong(TorrentMetadata.TorrentFile::length).sum();

        boolean hybrid = s.info().value().containsKey(BString.of("pieces"));
        byte[] sha256 = sha256Raw(s.infoRawBytes());
        if (hybrid) {
            BString v1Pieces = requireStringRaw(s.info(), "pieces");
            long expectedPieces = (length + pieceLength - 1) / pieceLength;
            if (v1Pieces.value().length / 20 != expectedPieces) {
                throw new IllegalArgumentException("hybrid piece count mismatch: v1 pieces has "
                        + (v1Pieces.value().length / 20) + " hashes, layout implies " + expectedPieces);
            }
            return new TorrentMetadata(sha1(s.infoRawBytes()), s.announce(), s.announceList(),
                    s.comment(), s.createdBy(), s.creationDateSec(), name, length, pieceLength,
                    v1Pieces.value(), privateFlagOf(s.info()), files, s.webSeeds(),
                    TorrentVersion.HYBRID, sha256);
        }
        return new TorrentMetadata(truncate20(sha256), s.announce(), s.announceList(), s.comment(),
                s.createdBy(), s.creationDateSec(), name, length, pieceLength,
                new byte[0], privateFlagOf(s.info()), files, s.webSeeds(),
                TorrentVersion.V2, sha256);
    }

    /** 深度优先展开 file tree（BDict 键序即路径序）；空串键的值 = 文件属性字典。 */
    private static void walkFileTree(BDict node, List<String> prefix,
            List<TorrentMetadata.TorrentFile> out) {
        for (Map.Entry<BString, BencodeValue> entry : node.value().entrySet()) {
            String key = entry.getKey().text();
            BencodeValue child = entry.getValue();
            if (child instanceof BDict childDict && childDict.get("") instanceof BDict attrs) {
                long fileLength = asInteger(require(attrs, "length"), "file tree length").value();
                if (fileLength < 0) {
                    throw new IllegalArgumentException("file tree length must be >= 0");
                }
                BencodeValue rootValue = require(attrs, "pieces root");
                if (!(rootValue instanceof BString root) || root.value().length != MerkleHashes.HASH_WIDTH) {
                    throw new IllegalArgumentException("pieces root must be a 32-byte string");
                }
                List<String> path = concat(prefix, key);
                boolean padding = !path.isEmpty() && path.get(0).startsWith(".pad");
                if (!padding) {
                    validatePathComponents(path);
                }
                out.add(new TorrentMetadata.TorrentFile(path, 0, fileLength, root.value(), padding));
            } else if (child instanceof BDict dir) {
                walkFileTree(dir, concat(prefix, key), out);
            } else {
                throw new IllegalArgumentException("file tree entry must be a dict: " + key);
            }
        }
    }

    /** 偏移累计 + 实文件 piece 对齐校验（填充文件吸收间隙，实文件一律对齐）。 */
    private static List<TorrentMetadata.TorrentFile> assignOffsets(
            List<TorrentMetadata.TorrentFile> walked, long pieceLength) {
        List<TorrentMetadata.TorrentFile> placed = new ArrayList<>(walked.size());
        long offset = 0;
        for (TorrentMetadata.TorrentFile file : walked) {
            if (!file.padding() && file.length() > 0 && offset % pieceLength != 0) {
                throw new IllegalArgumentException("v2 real file not aligned to piece boundary: "
                        + String.join("/", file.path()));
            }
            placed.add(new TorrentMetadata.TorrentFile(file.path(), offset, file.length(),
                    file.piecesRoot(), file.padding()));
            offset += file.length();
        }
        return placed;
    }

    /** 多 piece 文件必须有层带，且层带按 Merkle 归并与 pieces root 一致（防篡改）。 */
    private static void validateLayers(List<TorrentMetadata.TorrentFile> files,
            long pieceLength, @Nullable BDict layers) {
        for (TorrentMetadata.TorrentFile file : files) {
            if (file.length() <= pieceLength) {
                continue; // 单 piece 文件：层带可选（根即其唯一子树根）
            }
            if (layers == null) {
                throw new IllegalArgumentException("piece layers required for multi-piece file: "
                        + String.join("/", file.path()));
            }
            BencodeValue stripValue = layers.value().get(new BString(file.piecesRoot()));
            if (!(stripValue instanceof BString strip)) {
                throw new IllegalArgumentException("missing piece layer for multi-piece file: "
                        + String.join("/", file.path()));
            }
            int expectedPieces = (int) ((file.length() + pieceLength - 1) / pieceLength);
            byte[] stripBytes = strip.value();
            if (stripBytes.length != expectedPieces * MerkleHashes.HASH_WIDTH) {
                throw new IllegalArgumentException("piece layer length mismatch for "
                        + String.join("/", file.path()) + ": " + stripBytes.length
                        + " bytes, expected " + expectedPieces * MerkleHashes.HASH_WIDTH);
            }
            List<byte[]> layer = new ArrayList<>(expectedPieces);
            for (int i = 0; i < expectedPieces; i++) {
                layer.add(Arrays.copyOfRange(stripBytes, i * MerkleHashes.HASH_WIDTH,
                        (i + 1) * MerkleHashes.HASH_WIDTH));
            }
            if (!MessageDigest.isEqual(MerkleHashes.rootOfLayer(layer), file.piecesRoot())) {
                throw new IllegalArgumentException("piece layer hashes do not fold to pieces root: "
                        + String.join("/", file.path()));
            }
        }
    }

    private static boolean privateFlagOf(BDict info) {
        return info.value().containsKey(BString.of("private"))
                && asInteger(info.get("private"), "private").value() == 1;
    }

    /** 复用 v1 的路径穿越防护（仅实文件；填充文件由引擎内部消化）。 */
    private static void validatePathComponents(List<String> path) {
        for (String component : path) {
            if (component.isEmpty() || "..".equals(component) || component.contains("\\")
                    || component.contains("/") || component.contains(":")
                    || component.chars().anyMatch(c -> c < 0x20)
                    || isWindowsReserved(component)) {
                throw new IllegalArgumentException("unsafe path component in torrent: " + component);
            }
        }
    }

    private static List<String> concat(List<String> prefix, String key) {
        List<String> path = new ArrayList<>(prefix.size() + 1);
        path.addAll(prefix);
        path.add(key);
        return List.copyOf(path);
    }

    private static byte[] sha256Raw(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    private static byte[] truncate20(byte[] hash) {
        return Arrays.copyOf(hash, 20);
    }

    /**
     * 多文件清单（BEP 3）：info.files[] 的 path[]/length 映射为拼接流偏移。
     * 路径穿越防护（DESIGN §6.6）：拒绝空组件、`..`、绝对路径元素、反斜杠、
     * 盘符、Windows 保留设备名与控制字符——防恶意种子逃出目标目录。
     */
    private static List<TorrentMetadata.TorrentFile> parseFiles(BDict info) {
        BencodeValue filesValue = require(info, "files");
        if (!(filesValue instanceof BList fileList) || fileList.value().isEmpty()) {
            throw new IllegalArgumentException("info.files must be a non-empty list");
        }
        List<TorrentMetadata.TorrentFile> result = new ArrayList<>();
        long offset = 0;
        for (BencodeValue entryValue : fileList.value()) {
            if (!(entryValue instanceof BDict entry)) {
                throw new IllegalArgumentException("info.files entry must be a dict");
            }
            long fileLength = asInteger(require(entry, "length"), "files entry length").value();
            if (fileLength < 0) {
                throw new IllegalArgumentException("files entry length must be >= 0");
            }
            BencodeValue pathValue = require(entry, "path");
            if (!(pathValue instanceof BList pathList) || pathList.value().isEmpty()) {
                throw new IllegalArgumentException("files entry path must be a non-empty list");
            }
            List<String> path = new ArrayList<>();
            for (BencodeValue componentValue : pathList.value()) {
                String component = asString(componentValue, "files entry path component");
                if (component.isEmpty() || "..".equals(component) || component.contains("\\")
                        || component.contains("/") || component.contains(":")
                        || component.chars().anyMatch(c -> c < 0x20)
                        || isWindowsReserved(component)) {
                    throw new IllegalArgumentException("unsafe path component in torrent: " + component);
                }
                path.add(component);
            }
            result.add(new TorrentMetadata.TorrentFile(List.copyOf(path), offset, fileLength));
            offset += fileLength;
        }
        return result;
    }

    private static boolean isWindowsReserved(String component) {
        String stem = component.contains(".")
                ? component.substring(0, component.indexOf('.')) : component;
        return switch (stem.toUpperCase()) {
            case "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
                 "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
                 "LPT7", "LPT8", "LPT9" -> true;
            default -> false;
        };
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

    /** url-list（BEP 19）：单字符串（单源旧形态）或字符串列表（多源）归一化为列表。 */
    private static List<String> asUrlList(BencodeValue value) {
        if (value instanceof BString single) {
            return List.of(single.text());
        }
        if (!(value instanceof BList list)) {
            throw new IllegalArgumentException("url-list must be a byte string or a list of byte strings");
        }
        List<String> urls = new ArrayList<>();
        for (BencodeValue url : list.value()) {
            urls.add(asString(url, "url-list entry"));
        }
        return List.copyOf(urls);
    }

    /** piece layers（BEP 52 顶层）：root（32B 二进制键）→ 层哈希带。 */
    private static BDict asPieceLayers(BencodeValue value) {
        if (!(value instanceof BDict dict)) {
            throw new IllegalArgumentException("piece layers must be a dict");
        }
        return dict;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-1", e);
        }
    }
}
