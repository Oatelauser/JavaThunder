package io.github.oatelauser.thunder.tracker;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 可执行 jar 入口（内网分发的"opentracker 替代"，纯 Java 零依赖）：
 * <pre>
 *   java -jar javathunder-tracker-*-with-dependencies.jar
 *       [--port 6881] [--announce-interval 1800]
 *       [--udp-port 6881] [--whitelist &lt;hex-infohash&gt;[,&lt;hex&gt;...]|@file]
 * </pre>
 * {@code --port} 默认 6881（0=随机）；
 * {@code --announce-interval}（秒）默认 1800，同时决定 Peer 过期阈值（×2）。
 * {@code --udp-port} 缺省与 HTTP 同端口（BEP 15 UDP announce），传 0 关闭。
 * {@code --whitelist} 逗号分隔的 40 位 hex info-hash，或 {@code @file}（文件内
 * 按行/逗号分隔，# 注释）；启用后非白名单 torrent 的 announce 被拒绝。
 * 进程驻留直至 SIGINT/SIGTERM（shutdown hook 关停释放端口）。
 */
public final class TrackerMain {

    /** BitTorrent 客户端默认监听端口段起点。 */
    private static final int DEFAULT_PORT = 6881;

    /** BEP 3 常规 announce 间隔（30 分钟）。 */
    private static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 1800;

    /** 解析结果：udpPort=null 表示与 HTTP 同端口、0 表示关闭；whitelist=null 表示关闭。 */
    record Options(int port, int announceIntervalSeconds,
                   @Nullable Integer udpPort, @Nullable List<byte[]> whitelist) {
    }

    private TrackerMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: java -jar javathunder-tracker.jar"
                    + " [--port <n>] [--announce-interval <seconds>]"
                    + " [--udp-port <n>] [--whitelist <hex-infohash>[,<hex>...]|@file]");
            System.exit(2);
            return;
        }
        EmbeddedTracker server = EmbeddedTracker.start(
                wildcardAddress(), options.port(), options.announceIntervalSeconds());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "tracker-shutdown"));
        if (options.whitelist() != null) {
            server.enableWhitelist(options.whitelist());
        }
        System.out.printf("javathunder-tracker listening on 0.0.0.0:%d "
                        + "(announce interval %ds, peer expiry %ds)%n",
                server.port(), options.announceIntervalSeconds(), options.announceIntervalSeconds() * 2);
        System.out.printf("announce url: http://<this-host>:%d/announce%n", server.port());
        if (options.udpPort() == null || options.udpPort() > 0) {
            int bound = server.enableUdp(options.udpPort() == null ? 0 : options.udpPort());
            System.out.printf("udp announce url: udp://<this-host>:%d/announce%n", bound);
        } else {
            System.out.println("udp announce: disabled");
        }
        System.out.printf("whitelist: %s%n",
                options.whitelist() == null ? "disabled" : "enabled (" + options.whitelist().size() + " torrent(s))");
        System.out.printf("stats: http://<this-host>:%d/stats  metrics: http://<this-host>:%d/metrics%n",
                server.port(), server.port());
        Thread.currentThread().join(); // 驻留直至被杀（hook 负责关停）
    }

    /** 包可见：单测覆盖参数解析（含 --whitelist 文本值不得走数字校验）。 */
    static Options parse(String[] args) {
        int port = DEFAULT_PORT;
        int interval = DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
        Integer udpPort = null; // null = 与 HTTP 同端口；0 = 关闭
        List<byte[]> whitelist = null; // null = 关闭（全放行）
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--announce-interval" ->
                        interval = Integer.parseInt(requireValue(args, ++i, "--announce-interval"));
                case "--udp-port" -> udpPort = Integer.parseInt(requireValue(args, ++i, "--udp-port"));
                case "--whitelist" -> whitelist = parseWhitelist(requireText(args, ++i, "--whitelist"));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        return new Options(port, interval, udpPort, whitelist);
    }

    private static String requireValue(String[] args, int index, String option) {
        String value = requireText(args, index, option);
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got: " + value);
        }
        return value;
    }

    private static String requireText(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    /** 逗号分隔 hex，或 @file（行/逗号分隔，空行与 # 注释——含行内——忽略）。 */
    private static List<byte[]> parseWhitelist(String spec) {
        List<String> tokens = new ArrayList<>();
        try {
            String raw = spec.startsWith("@")
                    ? Files.readString(Path.of(spec.substring(1)))
                    : spec;
            for (String line : raw.split("\\R+")) {
                int comment = line.indexOf('#');
                String content = comment >= 0 ? line.substring(0, comment) : line;
                for (String token : content.split("[\\s,]+")) {
                    if (!token.isEmpty()) {
                        tokens.add(token);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read whitelist file: " + spec.substring(1));
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("whitelist is empty");
        }
        List<byte[]> hashes = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (!token.matches("[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("invalid info-hash (expect 40 hex chars): " + token);
            }
            hashes.add(HexFormat.of().parseHex(token.toLowerCase()));
        }
        return hashes;
    }

    private static InetAddress wildcardAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (IOException e) {
            throw new AssertionError("unreachable: literal 0.0.0.0", e);
        }
    }
}
