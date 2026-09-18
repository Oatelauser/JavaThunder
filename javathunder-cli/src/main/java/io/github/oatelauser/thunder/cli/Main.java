package io.github.oatelauser.thunder.cli;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.api.TorrentClient;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI 示例：javathunder download <torrent> [--dir <目录>]
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"download".equals(args[0])) {
            usageAndExit();
            return;
        }
        Path torrent = Path.of(args[1]);
        Path dir = targetDir(args);

        try (TorrentClient client = TorrentClient.create()) {
            DownloadTask task = client.download(torrent, DownloadOptions.defaults().targetDir(dir));
            task.addListener(new TaskListener() {
                @Override
                public void onProgress(ProgressSnapshot p) {
                    String eta = p.etaMillis() == null ? "--"
                            : String.format("%dm%02ds", p.etaMillis() / 60000, p.etaMillis() / 1000 % 60);
                    System.out.printf("\r%.1f%%  ↓ %d KB/s  ↑ %d KB/s  peers=%d  health=%.1f  eta=%s  ",
                            p.fraction() * 100, p.downloadRateBps() / 1024, p.uploadRateBps() / 1024,
                            p.connectedPeers(), p.availability(), eta);
                }

                @Override
                public void onStateChanged(TaskState from, TaskState to) {
                    System.out.printf("%n[%s]", to);
                }
            });
            DownloadResult result = task.future().join();
            System.out.printf("%ncompleted: %s (%d bytes, %ds)%n",
                    result.file(), result.bytes(), result.elapsed().toSeconds());
        }
    }

    /**
     * 解析可选 {@code --dir <目录>}（缺省 downloads）。多次出现时最后一个生效；
     * 悬空尾置 {@code --dir}（缺值）视为用法错误：打印 usage 并以非零码退出，
     * 不再静默落缺省目录（演示程序从简，不引参数解析库）。
     */
    private static Path targetDir(String[] args) {
        Path dir = Path.of("downloads");
        List<String> rest = List.of(args).subList(2, args.length);
        for (int i = 0; i < rest.size(); i++) {
            if ("--dir".equals(rest.get(i))) {
                if (i + 1 >= rest.size()) {
                    usageAndExit();
                }
                dir = Path.of(rest.get(i + 1));
            }
        }
        return dir;
    }

    private static void usageAndExit() {
        System.err.println("usage: javathunder download <torrent-file> [--dir <target-dir>]");
        System.exit(2);
    }

    private Main() {
    }
}
