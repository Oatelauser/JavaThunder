package io.github.oatelauser.thunder.dht.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kademlia 路由表（BEP 5 简化实现）：平面单桶（不做 k-bucket 前缀分裂），
 * 节点按最近活跃刷新（lru 语义），容量满（2048）按 XOR 距离淘汰最远者；
 * 未实现 stale ping 替换。对"找 peer"的查询路径够用，实现规模刻意收敛。
 */
public final class RoutingTable {

    public static final int K = 8;
    private static final long STALE_MILLIS = 15 * 60 * 1000;

    /**
     * 节点条目：id + 地址 + 最近活跃时间。
     */
    public record Entry(KrpcMessage.NodeId id, String host, int port, AtomicLong lastActive) {
        Entry(KrpcMessage.NodeId id, String host, int port) {
            this(id, host, port, new AtomicLong(System.currentTimeMillis()));
        }

        void touch() {
            lastActive.set(System.currentTimeMillis());
        }
    }

    private final KrpcMessage.NodeId self;
    private final Map<String, Entry> nodes = new ConcurrentHashMap<>();

    public RoutingTable(KrpcMessage.NodeId self) {
        this.self = self;
    }

    /**
     * 插入/刷新节点；容量满且全为新鲜节点时丢弃（真实实现应 ping 最旧者后替换，此处简化）。
     */
    public void offer(KrpcMessage.NodeId id, String host, int port) {
        if (id.equals(self)) {
            return;
        }
        Entry existing = nodes.get(id.hex());
        if (existing != null) {
            existing.touch();
            return;
        }
        // 距离最近的 K×桶容量上限内的候选才保留（简化空间；查找质量靠 offer 热度自然筛选）
        if (nodes.size() >= 2048) {
            KrpcMessage.NodeId farthest = farthestByDistance();
            if (farthest != null && compareDistances(id, farthest) > 0) {
                return; // 比最远者还远且表满：丢弃
            }
            nodes.remove(farthest.hex());
        }
        nodes.put(id.hex(), new Entry(id, host, port));
    }

    /**
     * 按 XOR 距离取离目标最近的 n 个活跃节点。
     */
    public List<Entry> nearest(KrpcMessage.NodeId target, int n) {
        long now = System.currentTimeMillis();
        List<Entry> alive = new ArrayList<>();
        for (Entry entry : nodes.values()) {
            if (now - entry.lastActive.get() < STALE_MILLIS) {
                alive.add(entry);
            }
        }
        alive.sort(Comparator.comparing(entry -> entry.id().distanceTo(target),
                RoutingTable::compareBytes));
        return alive.size() > n ? new ArrayList<>(alive.subList(0, n)) : alive;
    }

    public int size() {
        return nodes.size();
    }

    private KrpcMessage.NodeId farthestByDistance() {
        KrpcMessage.NodeId farthest = null;
        byte[] farthestDistance = null;
        for (Entry entry : nodes.values()) {
            byte[] distance = entry.id().distanceTo(self);
            if (farthestDistance == null || compareBytes(distance, farthestDistance) > 0) {
                farthest = entry.id();
                farthestDistance = distance;
            }
        }
        return farthest;
    }

    private int compareDistances(KrpcMessage.NodeId a, KrpcMessage.NodeId b) {
        return compareBytes(a.distanceTo(self), b.distanceTo(self));
    }

    /**
     * 无符号大端字典序（XOR 距离比较语义）。
     */
    static int compareBytes(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int left = a[i] & 0xFF;
            int right = b[i] & 0xFF;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return Integer.compare(a.length, b.length);
    }
}
