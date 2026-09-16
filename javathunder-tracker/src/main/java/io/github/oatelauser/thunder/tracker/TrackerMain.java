package io.github.oatelauser.thunder.tracker;

/**
 * 可执行 jar 入口（内网分发的"opentracker 替代"，纯 Java 零依赖）：
 * <pre>
 *   java -jar javathunder-tracker-*-with-dependencies.jar [--port 6881] [--announce-interval 1800]
 * </pre>
 * {@code --port} 默认 {@link TrackerServer#DEFAULT_PORT}（0=随机）；
 * {@code --announce-interval}（秒）默认 1800，同时决定 Peer 过期阈值（×2）。
 * 进程驻留直至 SIGINT/SIGTERM（shutdown hook 关停释放端口）。
 */
public final class TrackerMain {

    private TrackerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = TrackerServer.DEFAULT_PORT;
        int interval = TrackerServer.DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                    case "--announce-interval" ->
                            interval = Integer.parseInt(requireValue(args, ++i, "--announce-interval"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: java -jar javathunder-tracker.jar "
                    + "[--port <n>] [--announce-interval <seconds>]");
            System.exit(2);
            return;
        }
        TrackerServer server = TrackerServer.start(port, interval);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "tracker-shutdown"));
        System.out.printf("javathunder-tracker listening on 0.0.0.0:%d "
                        + "(announce interval %ds, peer expiry %ds)%n",
                server.port(), interval, interval * 2);
        System.out.printf("announce url: http://<this-host>:%d/announce%n", server.port());
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
