package com.example.thunder.demo;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.metainfo.TorrentParser;
import io.github.oatelauser.thunder.testkit.FakeSeeder;
import io.github.oatelauser.thunder.testkit.TorrentGenerator;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 仅 demo profile（--spring.profiles.active=demo）启动：在本机回环搭一个可下载的
 * 种子源，方便不依赖外网验证 REST/SSE 链路。生产集成不需要本类，也不需要内嵌
 * tracker——生产 tracker 是独立进程（java -jar javathunder-tracker-*-with-dependencies.jar）。
 *
 * <p>做的事：EmbeddedTracker（回环随机端口）→ TorrentGenerator 造 1MB 种子 →
 * FakeSeeder（真实线协议的种子方）注册到 tracker → 打印 curl 步骤。
 */
@Component
@Profile("demo")
public class DemoSeederRunner implements CommandLineRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DemoSeederRunner.class);

    private EmbeddedTracker tracker;
    private FakeSeeder seeder;
    private Path torrentFile;

    @Override
    public void run(String... args) throws Exception {
        Path dir = Path.of("demo");
        Files.createDirectories(dir);

        tracker = EmbeddedTracker.start();
        TorrentGenerator.GeneratedTorrent generated = TorrentGenerator.generate(
                dir, "demo-payload.bin", 1_000_000, tracker.announceUrl(), new Random(42));
        TorrentMetadata meta = TorrentParser.parse(Files.readAllBytes(generated.torrentFile()));
        seeder = FakeSeeder.start(generated.contentFile(), meta);
        seeder.announceTo(tracker);

        torrentFile = generated.torrentFile().toAbsolutePath();
        String source = torrentFile.toString().replace('\\', '/');
        String submit = "curl -s -X POST localhost:8080/api/downloads -H 'Content-Type: application/json' "
                + "-d '{\"source\": \"" + source + "\", \"targetDir\": \"demo/out\"}'";
        log.info("");
        log.info("======== JavaThunder demo swarm ready ========");
        log.info("1MB torrent + seeder running on loopback. Try:");
        log.info("  {}", submit);
        log.info("  curl -s localhost:8080/api/downloads/<id>            # progress");
        log.info("  curl -Ns localhost:8080/api/downloads/<id>/events    # SSE stream");
        log.info("  curl -s -X DELETE 'localhost:8080/api/downloads/<id>?deleteData=false'");
        log.info("==============================================");
    }

    @Override
    public void close() {
        if (seeder != null) {
            seeder.close();
        }
        if (tracker != null) {
            tracker.close();
        }
    }
}
