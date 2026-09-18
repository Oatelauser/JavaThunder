package io.github.oatelauser.thunder.core.internal.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Peer 握手（BEP 3 + BEP 6 + BEP 10 + BEP 52）：
 * {@code <pstrlen=19>"BitTorrent protocol"<8 字节保留位><20 info-hash><20 peer-id>}。
 * 保留位 reserved[5]：0x10 位声明扩展协议（BEP 10），0x04 位声明快速扩展（BEP 6）；
 * reserved[7]：0x10 位声明 v2 协议哈希交换（BEP 52 hash request/hashes/hash reject）。
 * 我们侧三位置位，对端位经 {@link #supportsExtensions}/{@link #supportsFastExtension}
 * /{@link #supportsV2} 读取；BEP 52 消息的使用以"双方都声明"为前提。其余位保持为零
 * （末字节 0x01 是 DHT/BEP 5，未启用）。
 */
public record Handshake(byte[] infoHash, byte[] peerId) {

    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    /** BEP 3：info-hash 与 peer-id 各 20 字节。 */
    private static final int HASH_BYTES = 20;
    /** 线格式偏移：info-hash 起始 = pstrlen(1) + 协议串(19) + 保留位(8)。 */
    private static final int INFO_HASH_OFFSET = 1 + PROTOCOL.length + 8;
    /** 线格式偏移：peer-id 起始 = info-hash 起始 + 20。 */
    private static final int PEER_ID_OFFSET = INFO_HASH_OFFSET + HASH_BYTES;
    private static final int WIRE_LENGTH = 1 + PROTOCOL.length + 8 + 20 + 20;
    /**
     * BEP 10 扩展协议位掩码（规范原文：bit 20 counting from 0, {@code reserved[5] & 0x10}）。
     */
    public static final int EXTENSION_BIT_MASK = 0x10;
    /**
     * BEP 6 快速扩展位掩码（bit 21 counting from 0, {@code reserved[5] & 0x04}）。
     */
    public static final int FAST_EXTENSION_BIT_MASK = 0x04;
    /**
     * BEP 52 v2 协议位掩码（bit 43 counting from 0, {@code reserved[7] & 0x10}，
     * 与 libtorrent 一致）：声明支持 hash request/hashes/hash reject 哈希交换。
     */
    public static final int V2_PROTOCOL_BIT_MASK = 0x10;
    /**
     * 线格式偏移：保留区 [20,28) 的第 5 字节 = wire[25]。
     */
    public static final int EXTENSION_BIT_OFFSET = 25;
    /**
     * 线格式偏移：保留区第 7 字节 = wire[27]（BEP 52 位所在）。
     */
    public static final int V2_BIT_OFFSET = 27;

    public Handshake {
        if (infoHash.length != HASH_BYTES || peerId.length != HASH_BYTES) {
            throw new IllegalArgumentException("info-hash and peer-id must be 20 bytes");
        }
        infoHash = infoHash.clone();
        peerId = peerId.clone();
    }

    public static byte[] encode(byte[] infoHash, byte[] peerId) {
        if (infoHash.length != HASH_BYTES || peerId.length != HASH_BYTES) {
            throw new IllegalArgumentException("info-hash and peer-id must be 20 bytes");
        }
        byte[] wire = new byte[WIRE_LENGTH];
        wire[0] = (byte) PROTOCOL.length;
        System.arraycopy(PROTOCOL, 0, wire, 1, PROTOCOL.length);
        // reserved[5]：0x10 扩展协议（BEP 10）+ 0x04 快速扩展（BEP 6）
        wire[EXTENSION_BIT_OFFSET] = EXTENSION_BIT_MASK | FAST_EXTENSION_BIT_MASK;
        // reserved[7]：0x10 v2 协议（BEP 52 哈希交换）
        wire[V2_BIT_OFFSET] = V2_PROTOCOL_BIT_MASK;
        System.arraycopy(infoHash, 0, wire, INFO_HASH_OFFSET, HASH_BYTES);
        System.arraycopy(peerId, 0, wire, PEER_ID_OFFSET, HASH_BYTES);
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
        byte[] infoHash = Arrays.copyOfRange(wire, INFO_HASH_OFFSET, PEER_ID_OFFSET);
        byte[] peerId = Arrays.copyOfRange(wire, PEER_ID_OFFSET, WIRE_LENGTH);
        return new Handshake(infoHash, peerId);
    }

    /**
     * 对端是否声明支持扩展协议（BEP 10：reserved[5] & 0x10）。
     */
    public static boolean supportsExtensions(byte[] wire) {
        return wire.length > EXTENSION_BIT_OFFSET
                && (wire[EXTENSION_BIT_OFFSET] & EXTENSION_BIT_MASK) != 0;
    }

    /**
     * 对端是否声明支持快速扩展（BEP 6：reserved[5] & 0x04）。
     */
    public static boolean supportsFastExtension(byte[] wire) {
        return wire.length > EXTENSION_BIT_OFFSET
                && (wire[EXTENSION_BIT_OFFSET] & FAST_EXTENSION_BIT_MASK) != 0;
    }

    /**
     * 对端是否声明支持 v2 协议哈希交换（BEP 52：reserved[7] & 0x10）。
     */
    public static boolean supportsV2(byte[] wire) {
        return wire.length > V2_BIT_OFFSET && (wire[V2_BIT_OFFSET] & V2_PROTOCOL_BIT_MASK) != 0;
    }
}
