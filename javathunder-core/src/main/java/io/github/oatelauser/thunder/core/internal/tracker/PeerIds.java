package io.github.oatelauser.thunder.core.internal.tracker;

import java.security.SecureRandom;

/** Peer ID 生成，BEP 20 Azureus 风格前缀 {@code -JT0001-} + 12 位随机字母数字。 */
public final class PeerIds {

    public static final String CLIENT_PREFIX = "-JT0001-";

    private static final String ALPHANUMERIC =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PeerIds() {
    }

    public static byte[] generate() {
        byte[] id = new byte[20];
        byte[] prefix = CLIENT_PREFIX.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, id, 0, prefix.length);
        for (int i = prefix.length; i < id.length; i++) {
            id[i] = (byte) ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length()));
        }
        return id;
    }
}
