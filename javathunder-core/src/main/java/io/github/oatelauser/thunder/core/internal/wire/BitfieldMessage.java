package io.github.oatelauser.thunder.core.internal.wire;

import java.util.Arrays;

/**
 * 对端初始持有位图（ID 5，仅允许作为握手后首条消息）。高位在前，尾部空闲位为零。
 */
public record BitfieldMessage(byte[] bits) implements PeerWireMessage {

    public BitfieldMessage {
        bits = bits.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BitfieldMessage other && Arrays.equals(bits, other.bits);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bits);
    }
}
