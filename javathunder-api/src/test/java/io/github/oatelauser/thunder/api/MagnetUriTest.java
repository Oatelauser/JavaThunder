package io.github.oatelauser.thunder.api;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MagnetUriTest {

    @Test
    void parsesHexInfoHashWithTrackersAndName() {
        String hex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4";
        MagnetUri magnet = MagnetUri.parse(
            "magnet:?xt=urn:btih:" + hex + "&dn=model-x&tr=http%3A%2F%2Ft1%2Fannounce&tr=udp%3A%2F%2Ft2%3A6969");

        assertArrayEquals(HexFormat.of().parseHex(hex), magnet.infoHash());
        assertEquals("model-x", magnet.displayName());
        assertEquals(2, magnet.trackers().size());
        assertEquals("http://t1/announce", magnet.trackers().get(0));
        assertEquals("udp://t2:6969", magnet.trackers().get(1));
    }

    @Test
    void parsesBase32InfoHash() {
        // base32(20 字节) 恒为 32 字符；向量 = hex a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4
        String base32 = "UGZMHVHF6YDRQKJ2JNOG27UPSCQ3FQ6U";
        MagnetUri magnet = MagnetUri.parse("magnet:?xt=urn:btih:" + base32);
        assertArrayEquals(HexFormat.of().parseHex("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"),
            magnet.infoHash());
    }

    @Test
    void roundTripsBase32AgainstKnownBytes() {
        // 'A'*32 → 160 个零位 → 全零 20 字节
        byte[] decoded = MagnetUri.base32Decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertEquals(20, decoded.length);
        for (byte b : decoded) {
            assertEquals(0, b);
        }
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse("http://x"));
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse("magnet:?dn=x"));
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse("magnet:?xt=urn:sha1:abc"));
        assertThrows(IllegalArgumentException.class,
            () -> MagnetUri.parse("magnet:?xt=urn:btih:tooshort"));
        assertThrows(IllegalArgumentException.class,
            () -> MagnetUri.parse("magnet:?xt=urn:btih:NOT@BASE32!!!NOT@BASE32!!!NOT@BA"));
    }
}
