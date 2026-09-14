package io.github.oatelauser.thunder.cli;

import io.github.oatelauser.thunder.api.DownloadOptions;
import io.github.oatelauser.thunder.api.DownloadResult;
import io.github.oatelauser.thunder.api.DownloadTask;
import io.github.oatelauser.thunder.api.ProgressSnapshot;
import io.github.oatelauser.thunder.api.TaskListener;
import io.github.oatelauser.thunder.api.TaskState;
import io.github.oatelauser.thunder.core.internal.client.DefaultTorrentClient;

import java.nio.file.Path;
import java.util.List;

/** CLI 示例：javathunder download <torrent> [--dir <目录>] */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"download".equals(args[0])) {
            System.err.println("usage: javathunder download <torrent-file> [--dir <target-dir>]");
            System.exit(2);
            return;
        }
        Path torrent = Path.of(args[1]);
        Path dir = Path.of("downloads");
        List<String> rest = List.of(args).subList(2, args.length);
        for (int i = 0; i + 1 < rest.size(); i++) {
            if ("--dir".equals(rest.get(i))) {
                dir = Path.of(rest.get(i + 1));
            }
        }

        try (DefaultTorrentClient client = DefaultTorrentClient.create()) {
            DownloadTask task = client.download(torrent, DownloadOptions.defaults().targetDir(dir));
            task.addListener(new TaskListener() {
                @Override
                public void onProgress(ProgressSnapshot p) {
                    System.out.printf("\r%.1f%%  ↓ %d KB/s  ↑ %d KB/s  peers=%d  health=%.1f  ",
                        p.fraction() * 100, p.downloadRateBps() / 1024, p.uploadRateBps() / 1024,
                        p.connectedPeers(), p.availability());
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

    private Main() {
    }
}
