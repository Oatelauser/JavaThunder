package io.github.oatelauser.thunder.core.internal.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Peer 握手（BEP 3 + BEP 10）：
 * {@code <pstrlen=19>"BitTorrent protocol"<8 字节保留位><20 info-hash><20 peer-id>}。
 * 保留位 reserved[5] 的 0x10 位（从右数第 20 bit）置位声明扩展协议支持（BEP 10）；
 * 阶段 2 内其余位保持为零（末字节 0x01 是 DHT/BEP 5，未启用）。
 */
public record Handshake(byte[] infoHash, byte[] peerId) {

    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final int WIRE_LENGTH = 1 + PROTOCOL.length + 8 + 20 + 20;
    /**
     * BEP 10 扩展协议位掩码（规范原文：bit 20 counting from 0, {@code reserved[5] & 0x10}）。
     */
    public static final int EXTENSION_BIT_MASK = 0x10;
    /**
     * 线格式偏移：保留区 [20,28) 的第 5 字节 = wire[25]。
     */
    public static final int EXTENSION_BIT_OFFSET = 25;

    public Handshake {
        if (infoHash.length != 20 || peerId.length != 20) {
            throw new IllegalArgumentException("info-hash and peer-id must be 20 bytes");
        }
        infoHash = infoHash.clone();
        peerId = peerId.clone();
    }

    public static byte[] encode(byte[] infoHash, byte[] peerId) {
        if (infoHash.length != 20 || peerId.length != 20) {
            throw new IllegalArgumentException("info-hash and peer-id must be 20 bytes");
        }
        byte[] wire = new byte[WIRE_LENGTH];
        wire[0] = (byte) PROTOCOL.length;
        System.arraycopy(PROTOCOL, 0, wire, 1, PROTOCOL.length);
        wire[EXTENSION_BIT_OFFSET] = EXTENSION_BIT_MASK; // reserved[5] & 0x10：声明扩展能力（BEP 10）
        System.arraycopy(infoHash, 0, wire, 28, 20);
        System.arraycopy(peerId, 0, wire, 48, 20);
        return wire;
    }

    public static Handshake decode(byte[] wire) {
        if (wire.length != WIRE_LENGTH) {
            throw new PeerWireException("handshake must be " + WIRE_LENGTH + " bytes, got " + wire.length);
        }
        if ((wire[0] & 0xFF) != PROTOCOL.length) {
            throw new PeerWireException("unsupported pstrlen: " + (wire[0] & 0xFF));
        }
        if (!Arrays.equals(wire, 1, 1 + PROTOCOL.length, PROTOCOL, 0, PROTOCOL.length)) {
            throw new PeerWireException("handshake protocol string mismatch");
        }
        byte[] infoHash = Arrays.copyOfRange(wire, 28, 48);
        byte[] peerId = Arrays.copyOfRange(wire, 48, 68);
        return new Handshake(infoHash, peerId);
    }

    /**
     * 对端是否声明支持扩展协议（BEP 10：reserved[5] & 0x10）。
     */
    public static boolean supportsExtensions(byte[] wire) {
        return wire.length > EXTENSION_BIT_OFFSET
                && (wire[EXTENSION_BIT_OFFSET] & EXTENSION_BIT_MASK) != 0;
    }
}
