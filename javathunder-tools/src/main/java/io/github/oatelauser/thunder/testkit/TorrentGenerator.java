package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.bencode.BDict;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BencodeValue;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;


/**
 * 生成随机内容文件与配套 .torrent，用于回环测试。
 */
public final class TorrentGenerator {

    public static final int DEFAULT_PIECE_LENGTH = 256 * 1024;

    private TorrentGenerator() {
    }

    public static GeneratedTorrent generate(Path dir, String name, int sizeBytes,
            String announceUrl, Random random) throws IOException {
        return generate(dir, name, sizeBytes, DEFAULT_PIECE_LENGTH, announceUrl, random);
    }

    public static GeneratedTorrent generate(Path dir, String name, int sizeBytes, int pieceLength,
            String announceUrl, Random random) throws IOException {
        return generate(dir, name, sizeBytes, pieceLength, announceUrl, List.of(), random);
    }

    /**
     * 完整形态：可附带 WebSeed 兜底源（BEP 19 顶层 url-list）。{@code announceUrl} 传
     * null 且 {@code webSeeds} 非空时生成纯 WebSeed 种子（无 tracker，仅 HTTP 源）。
     */
    public static GeneratedTorrent generate(Path dir, String name, int sizeBytes, int pieceLength,
            @Nullable String announceUrl, List<String> webSeeds, Random random) throws IOException {
        byte[] content = new byte[sizeBytes];
        random.nextBytes(content);
        Path contentFile = dir.resolve(name);
        Files.write(contentFile, content);

        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of(name));
        info.put(BString.of("piece length"), new BInteger(pieceLength));
        info.put(BString.of("length"), new BInteger(sizeBytes));
        info.put(BString.of("pieces"), new BString(sha1Pieces(content, pieceLength)));
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        if (announceUrl != null) {
            top.put(BString.of("announce"), BString.of(announceUrl));
        }
        if (!webSeeds.isEmpty()) {
            // 单文件形态恒写 BList；单 url 裸字符串形态由 generateMultiFile 覆盖——
            // BEP 19 两种合法线形态各留一臂，勿统一。
            List<BencodeValue> urls = new ArrayList<>();
            for (String url : webSeeds) {
                urls.add(BString.of(url));
            }
            top.put(BString.of("url-list"), new BList(urls));
        }
        top.put(BString.of("info"), new BDict(info));
        Path torrentFile = dir.resolve(name + ".torrent");
        Files.write(torrentFile, Bencode.encode(new BDict(top)));
        return new GeneratedTorrent(contentFile, torrentFile, pieceCount(content.length, pieceLength));
    }

    /**
     * 生成多文件种子：name 根目录下按 [path, sizeBytes] 写随机内容，Piece 覆盖拼接流。
     */
    public static GeneratedMultiFileTorrent generateMultiFile(Path dir, String name, List<List<Object>> specs,
            int pieceLength, String announceUrl, Random random) throws IOException {
        return generateMultiFile(dir, name, specs, pieceLength, announceUrl, List.of(), random);
    }

    /**
     * 完整形态：可附带 WebSeed 源（BEP 19 顶层 url-list，目录形态——base 供拼接相对路径）。
     * {@code announceUrl} 传 null 且 urlList 非空时生成纯 WebSeed 种子。
     */
    public static GeneratedMultiFileTorrent generateMultiFile(Path dir, String name, List<List<Object>> specs,
            int pieceLength, @Nullable String announceUrl, List<String> urlList, Random random)
            throws IOException {
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        List<BencodeValue> fileDicts = new ArrayList<>();
        for (List<Object> spec : specs) {
            writeMultiFileEntry(dir, name, spec, random, concatenated, fileDicts);
        }
        byte[] stream = concatenated.toByteArray();
        Map<BString, BencodeValue> info = new TreeMap<>(BString.UNSIGNED_ORDER);
        info.put(BString.of("name"), BString.of(name));
        info.put(BString.of("piece length"), new BInteger(pieceLength));
        info.put(BString.of("pieces"), new BString(sha1Pieces(stream, pieceLength)));
        info.put(BString.of("files"), new BList(fileDicts));
        Map<BString, BencodeValue> top = new TreeMap<>(BString.UNSIGNED_ORDER);
        if (announceUrl != null) {
            top.put(BString.of("announce"), BString.of(announceUrl));
        }
        if (!urlList.isEmpty()) {
            // BEP 19 允许 url-list 为裸字符串或列表：单 url 写裸字符串（目录 base 用例），
            // 多 url 写列表——与单文件 generate() 恒写列表互补，覆盖两种线形态。
            top.put(BString.of("url-list"), urlList.size() == 1
                    ? BString.of(urlList.get(0))
                    : new BList(urlList.stream().map(BString::of).collect(Collectors.toList())));
        }
        top.put(BString.of("info"), new BDict(info));
        Path torrentFile = dir.resolve(name + ".torrent");
        Files.write(torrentFile, Bencode.encode(new BDict(top)));
        return new GeneratedMultiFileTorrent(dir.resolve(name), torrentFile,
                pieceCount(stream.length, pieceLength));
    }

    /** 写出一个 spec 条目的随机内容文件并追加 file 字典与拼接流字节。 */
    @SuppressWarnings("unchecked")
    private static void writeMultiFileEntry(Path dir, String name, List<Object> spec,
            Random random, ByteArrayOutputStream concatenated, List<BencodeValue> fileDicts)
            throws IOException {
        List<String> path = (List<String>) spec.get(0);
        int sizeBytes = (Integer) spec.get(1);
        byte[] content = new byte[sizeBytes];
        random.nextBytes(content);
        Path target = dir.resolve(name);
        for (String component : path) {
            target = target.resolve(component);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        concatenated.writeBytes(content);
        Map<BString, BencodeValue> fileDict = new TreeMap<>(BString.UNSIGNED_ORDER);
        fileDict.put(BString.of("length"), new BInteger(sizeBytes));
        List<BencodeValue> pathElements = new ArrayList<>();
        for (String component : path) {
            pathElements.add(BString.of(component));
        }
        fileDict.put(BString.of("path"), new BList(pathElements));
        fileDicts.add(new BDict(fileDict));
    }

    /** 件数 = ceil(总长 / 件长)，末件允许截断。 */
    private static int pieceCount(long totalBytes, int pieceLength) {
        return (int) ((totalBytes + pieceLength - 1) / pieceLength);
    }

    /** V1 pieces 字段：对拼接流按件长切块，逐件 SHA-1 后 20 字节哈希串联。 */
    private static byte[] sha1Pieces(byte[] stream, int pieceLength) {
        byte[] pieces = new byte[pieceCount(stream.length, pieceLength) * 20];
        for (int p = 0; p * pieceLength < stream.length; p++) {
            int from = p * pieceLength;
            byte[] hash = sha1(stream, from, Math.min(from + pieceLength, stream.length));
            System.arraycopy(hash, 0, pieces, p * 20, 20);
        }
        return pieces;
    }

    private static byte[] sha1(byte[] data, int from, int to) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data, from, to - from);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record GeneratedTorrent(Path contentFile, Path torrentFile, int pieceCount) {
    }

    /**
     * 多文件形态：目录树 + 各文件真实内容 + 对应 .torrent（返回根目录）。
     */
    public record GeneratedMultiFileTorrent(Path rootDir, Path torrentFile, int pieceCount) {
    }

}
