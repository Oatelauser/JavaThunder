package io.github.oatelauser.thunder.tracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CLI 参数解析：默认值、--udp-port 三态（缺省=同端口 / 0=关闭 / n=指定）、
 * --whitelist 的内联 hex / @file / 非法输入（文本值不得走数字校验）。
 */
class TrackerMainTest {

    private static final String HASH_A = "00".repeat(20);
    private static final String HASH_B = "ff".repeat(20);

    @Test
    void defaultsMatchProductionForm() {
        TrackerMain.Options options = TrackerMain.parse(new String[0]);
        assertEquals(6881, options.port(), "BitTorrent 默认端口段起点");
        assertEquals(1800, options.announceIntervalSeconds(), "BEP 3 常规 30 分钟间隔");
        assertNull(options.udpPort(), "缺省 = 与 HTTP 同端口");
        assertNull(options.whitelist(), "缺省 = 关闭白名单");
    }

    @Test
    void udpPortTriState() {
        assertEquals(7000, TrackerMain.parse(new String[]{"--udp-port", "7000"}).udpPort());
        assertEquals(0, TrackerMain.parse(new String[]{"--udp-port", "0"}).udpPort(), "0 = 关闭");
    }

    @Test
    void whitelistAcceptsInlineHexFileAndRejectsGarbage(@TempDir Path temp) throws Exception {
        List<byte[]> inline = TrackerMain.parse(
                new String[]{"--whitelist", HASH_A + "," + HASH_B.toUpperCase()}).whitelist();
        assertEquals(2, inline.size());
        assertArrayEquals(HexFormat.of().parseHex(HASH_A), inline.get(0));
        assertArrayEquals(HexFormat.of().parseHex(HASH_B), inline.get(1));

        Path file = temp.resolve("whitelist.txt");
        Files.writeString(file, "# 内网白名单\n" + HASH_A + "\n" + HASH_B + ",\n");
        List<byte[]> fromFile = TrackerMain.parse(new String[]{"--whitelist", "@" + file}).whitelist();
        assertEquals(2, fromFile.size(), "注释与空行被忽略");

        assertThrows(IllegalArgumentException.class,
                () -> TrackerMain.parse(new String[]{"--whitelist", "not-hex"}));
        assertThrows(IllegalArgumentException.class,
                () -> TrackerMain.parse(new String[]{"--whitelist", "@missing-file"}));
        assertThrows(IllegalArgumentException.class,
                () -> TrackerMain.parse(new String[]{"--bogus"}));
        assertThrows(IllegalArgumentException.class,
                () -> TrackerMain.parse(new String[]{"--port"}));
    }
}
