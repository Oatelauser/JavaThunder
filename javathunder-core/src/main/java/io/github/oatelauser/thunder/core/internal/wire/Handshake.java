package io.github.oatelauser.thunder.core.internal.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Peer 握手（BEP 3）：
 * {@code <pstrlen=19>"BitTorrent protocol"<8 字节保留位><20 info-hash><20 peer-id>}。
 * 阶段 1 保留位全零（阶段 2 置 DHT 扩展位）。
 */
public record Handshake(byte[] infoHash, byte[] peerId) {

    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final int WIRE_LENGTH = 1 + PROTOCOL.length + 8 + 20 + 20;

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
        // 保留位 [20,28) 保持为零
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
}
