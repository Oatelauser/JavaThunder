package io.github.oatelauser.thunder.tracker;

/**
 * 可执行 jar 入口（内网分发的"opentracker 替代"，纯 Java 零依赖）：
 * <pre>
 *   java -jar javathunder-tracker-*-with-dependencies.jar
 *       [--port 6881] [--announce-interval 1800] [--udp-port 6881]
 * </pre>
 * {@code --port} 默认 {@link TrackerServer#DEFAULT_PORT}（0=随机）；
 * {@code --announce-interval}（秒）默认 1800，同时决定 Peer 过期阈值（×2）。
 * {@code --udp-port} 缺省与 HTTP 同端口（BEP 15 UDP announce），传 0 关闭。
 * 进程驻留直至 SIGINT/SIGTERM（shutdown hook 关停释放端口）。
 */
public final class TrackerMain {

    private TrackerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = TrackerServer.DEFAULT_PORT;
        int interval = TrackerServer.DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
        Integer udpPort = null; // null = 与 HTTP 同端口；0 = 关闭
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                    case "--announce-interval" ->
                            interval = Integer.parseInt(requireValue(args, ++i, "--announce-interval"));
                    case "--udp-port" -> udpPort = Integer.parseInt(requireValue(args, ++i, "--udp-port"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: java -jar javathunder-tracker.jar"
                    + " [--port <n>] [--announce-interval <seconds>] [--udp-port <n>]");
            System.exit(2);
            return;
        }
        TrackerServer server = TrackerServer.start(port, interval);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "tracker-shutdown"));
        System.out.printf("javathunder-tracker listening on 0.0.0.0:%d "
                        + "(announce interval %ds, peer expiry %ds)%n",
                server.port(), interval, interval * 2);
        System.out.printf("announce url: http://<this-host>:%d/announce%n", server.port());
        if (udpPort == null || udpPort > 0) {
            int bound = server.enableUdp(udpPort == null ? 0 : udpPort);
            System.out.printf("udp announce url: udp://<this-host>:%d/announce%n", bound);
        } else {
            System.out.println("udp announce: disabled");
        }
        Thread.currentThread().join(); // 驻留直至被杀（hook 负责关停）
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        String value = args[index];
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got: " + value);
        }
        return value;
    }
}
