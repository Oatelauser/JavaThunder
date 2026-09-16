package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.core.internal.bencode.Bencode;
import io.github.oatelauser.thunder.core.internal.bencode.BInteger;
import io.github.oatelauser.thunder.core.internal.bencode.BList;
import io.github.oatelauser.thunder.core.internal.bencode.BString;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Builder;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.Parsed;
import io.github.oatelauser.thunder.dht.internal.KrpcMessage.PeerAddr;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KrpcMessageTest {

    @Test
    void queryRoundTrip() {
        byte[] tx = {0x12, 0x34};
        NodeId id = DhtClient.randomId();
        byte[] wire = Builder.query(tx, "ping").id(id).encode();

        Parsed parsed = KrpcMessage.parse(wire);
        assertEquals("q", parsed.type());
        assertEquals("ping", parsed.method());
        assertArrayEquals(tx, parsed.transactionId());
        assertEquals(id, parsed.nodeId());
    }

    @Test
    void getPeersResponseWithNodesAndValuesAndToken() {
        byte[] tx = {9, 9};
        NodeId id = DhtClient.randomId();
        byte[] token = {7, 7, 7};
        // 两个紧凑节点 + 一个紧凑 peer
        // 两个紧凑节点 + 一个紧凑 peer（IP 各占 4 字节）
        byte[] node1 = new byte[26];
        node1[20] = 127;
        node1[21] = 0;
        node1[22] = 0;
        node1[23] = 1;
        node1[24] = 0x1F;
        node1[25] = (byte) 0x90;
        byte[] node2 = new byte[26];
        node2[23] = 2;
        node2[25] = 1;
        byte[] peer = {(byte) 192, (byte) 168, 0, 5, 0x1F, (byte) 0x91};
        byte[] wire = Builder.response(tx)
            .id(id)
            .result("token", new BString(token))
            .result("nodes", new BString(concat(node1, node2)))
            .result("values", new BList(
                List.of(new BString(peer))))
            .encode();

        Parsed parsed = KrpcMessage.parse(wire);
        assertEquals("r", parsed.type());
        assertEquals(2, parsed.nodes().size());
        assertEquals("127.0.0.1", parsed.nodes().get(0).host());
        assertEquals(8080, parsed.nodes().get(0).port());
        assertEquals(1, parsed.values().size());
        assertEquals("192.168.0.5", parsed.values().get(0).host());
        assertEquals(8081, parsed.values().get(0).port());
        assertArrayEquals(token, parsed.token());
    }

    @Test
    void errorPayloadParses() {
        byte[] tx = {1};
        Parsed parsed = KrpcMessage.parse(
            Builder.error(tx, 201, "Method Unknown").encode());
        assertEquals("e", parsed.type());
        assertEquals(List.of(201, "Method Unknown"), parsed.error());
    }

    @Test
    void distanceIsXor() {
        byte[] a = HexFormat.of().parseHex("0000000000000000000000000000000000000001");
        byte[] b = HexFormat.of().parseHex("0000000000000000000000000000000000000003");
        byte[] distance = new NodeId(a).distanceTo(new NodeId(b));
        assertEquals(2, distance[19]); // 1 XOR 3 = 2
    }

    @Test
    void malformedRejections() {
        assertThrows(IllegalArgumentException.class, () -> KrpcMessage.parse(Bencode.encode(
            new BInteger(5))));
        assertThrows(IllegalArgumentException.class,
            () -> KrpcMessage.parse("d1:ad2:id20:aaaaaaaaaaaaaaaaaaaae1:t2:aae".getBytes()));
        // 缺 y
        assertTrue(true);
    }

    @Test
    void nodeIdRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new NodeId(new byte[19]));
    }

    @Test
    void compactRejectsWrongSizes() {
        assertThrows(IllegalArgumentException.class, () -> PeerAddr.compact6(new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> PeerAddr.compact26(new byte[25]));
        assertNull(new Parsed(new byte[1], "r", null, null, null, null).token());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
