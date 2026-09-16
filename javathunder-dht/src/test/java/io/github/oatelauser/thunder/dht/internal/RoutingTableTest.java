package io.github.oatelauser.thunder.dht.internal;

import io.github.oatelauser.thunder.dht.internal.KrpcMessage.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直测简化 Kademlia 路由表：offer 插入/合并/淘汰、nearest 的 XOR 排序与截断、
 * compareBytes 的大端无符号比较。NodeId 全部用确定性字节构造。
 */
class RoutingTableTest {

    /** 20 字节 id：首字节 first，其余 0（首字节即大端幅值，距离模式确定）。 */
    private static NodeId idAt(int first) {
        byte[] bytes = new byte[20];
        bytes[0] = (byte) first;
        return new NodeId(bytes);
    }

    private static List<NodeId> idsOf(List<RoutingTable.Entry> entries) {
        return entries.stream().map(RoutingTable.Entry::id).toList();
    }

    @Test
    void offerSingleNodeMakesItVisibleInSizeAndNearest() {
        RoutingTable table = new RoutingTable(idAt(0x00));
        assertEquals(0, table.size());
        assertTrue(table.nearest(idAt(0x42), RoutingTable.K).isEmpty());

        table.offer(idAt(0x7F), "10.0.0.1", 7001);
        assertEquals(1, table.size());
        List<RoutingTable.Entry> found = table.nearest(idAt(0x42), RoutingTable.K);
        assertEquals(1, found.size());
        assertEquals(idAt(0x7F), found.get(0).id());
        assertEquals("10.0.0.1", found.get(0).host());
        assertEquals(7001, found.get(0).port());
    }

    @Test
    void offerOfSelfIdIsIgnored() {
        RoutingTable table = new RoutingTable(idAt(0x2A));
        table.offer(idAt(0x2A), "10.0.0.1", 7001);
        assertEquals(0, table.size());
        assertTrue(table.nearest(idAt(0x2A), RoutingTable.K).isEmpty());
    }

    @Test
    void nearestSortsByUnsignedXorDistanceAndTruncatesToN() {
        RoutingTable table = new RoutingTable(idAt(0x00));
        table.offer(idAt(0x05), "h5", 5);
        table.offer(idAt(0x03), "h3", 3);
        table.offer(idAt(0xFF), "hff", 255);
        table.offer(idAt(0x01), "h1", 1);
        table.offer(idAt(0x80), "h80", 128);
        table.offer(idAt(0x7F), "h7f", 127);

        // 目标全零 → 距离即首字节；0x7F(127) < 0x80(128) 验证无符号比较（有符号会反序）
        assertEquals(List.of(idAt(0x01), idAt(0x03), idAt(0x05), idAt(0x7F)),
            idsOf(table.nearest(idAt(0x00), 4)));
        assertEquals(List.of(idAt(0x01), idAt(0x03)), idsOf(table.nearest(idAt(0x00), 2)));
        assertEquals(6, table.nearest(idAt(0x00), 10).size()); // n 超过表大小返回全部
    }

    @Test
    void duplicateOfferMergesIntoExistingEntry() {
        RoutingTable table = new RoutingTable(idAt(0x00));
        table.offer(idAt(0x11), "10.0.0.1", 7001);
        table.offer(idAt(0x11), "10.0.0.2", 7002); // 同 id 重复 offer：仅刷新活跃，不换地址

        assertEquals(1, table.size());
        RoutingTable.Entry entry = table.nearest(idAt(0x00), RoutingTable.K).get(0);
        assertEquals("10.0.0.1", entry.host());
        assertEquals(7001, entry.port());
    }

    @Test
    void nearestDropsStaleEntries() {
        RoutingTable table = new RoutingTable(idAt(0x00));
        table.offer(idAt(0x01), "h1", 1);
        table.offer(idAt(0x02), "h2", 2);
        table.offer(idAt(0x03), "h3", 3);
        RoutingTable.Entry stale = table.nearest(idAt(0x00), RoutingTable.K).get(0);
        stale.lastActive().set(System.currentTimeMillis() - 16 * 60 * 1000); // 超过 15 分钟阈值

        List<RoutingTable.Entry> alive = table.nearest(idAt(0x00), RoutingTable.K);
        assertEquals(2, alive.size());
        assertTrue(alive.stream().noneMatch(e -> e.id().equals(idAt(0x01))));
    }

    @Test
    void fullTableDropsFartherNodesAndEvictsFarthestForCloserOnes() {
        RoutingTable table = new RoutingTable(idAt(0x00)); // self 全零
        for (int i = 1; i <= 2048; i++) { // 两字节幅值 0x0001..0x0800，共 2048 个
            byte[] bytes = new byte[20];
            bytes[0] = (byte) (i >> 8);
            bytes[1] = (byte) i;
            table.offer(new NodeId(bytes), "h", 1);
        }
        assertEquals(2048, table.size());

        byte[] far = new byte[20];
        far[0] = (byte) 0xFF; // 比现有最远者 0x0800 更远且表满：应被丢弃
        table.offer(new NodeId(far), "far", 1);
        assertEquals(2048, table.size());

        byte[] close = new byte[20];
        close[2] = 1; // 幅值 1 < 0x0800：挤掉最远者后插入
        NodeId closeId = new NodeId(close);
        table.offer(closeId, "close", 1);
        assertEquals(2048, table.size());
        assertTrue(table.nearest(idAt(0x00), 5).stream().anyMatch(e -> e.id().equals(closeId)));
    }

    @Test
    void compareBytesIsUnsignedBigEndianWithLengthTieBreak() {
        assertTrue(RoutingTable.compareBytes(new byte[]{0x00, (byte) 0xFF},
            new byte[]{0x01, 0x00}) < 0); // 高位字节先决，不看低位大小
        assertTrue(RoutingTable.compareBytes(new byte[]{(byte) 0xFF},
            new byte[]{0x01}) > 0); // 无符号：255 > 1（有符号 byte 会判反）
        assertEquals(0, RoutingTable.compareBytes(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}));
        assertTrue(RoutingTable.compareBytes(new byte[]{1, 2}, new byte[]{1, 2, 3}) < 0);
        assertTrue(RoutingTable.compareBytes(new byte[]{1, 2, 3}, new byte[]{1, 2}) > 0);
    }
}
