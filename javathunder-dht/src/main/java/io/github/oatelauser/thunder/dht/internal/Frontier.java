package io.github.oatelauser.thunder.dht.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代查找的候选边界（frontier）：Kademlia 游走骨架，get_peers 与 find_node 共用。
 * 按与目标的异或距离维护候选，{@link #takeBatch()} 每轮弹出 alpha 个未查询过的
 * 节点，{@link #tighten()} 在轮末重排并截断，防候选随响应无限膨胀。
 */
final class Frontier {

    /** Kademlia 并发度：每轮同时询问的最近节点数。 */
    static final int ALPHA = 3;
    private static final int MAX_ENTRIES = 32;

    private final KrpcMessage.NodeId target;
    private final List<RoutingTable.Entry> entries;
    private final Set<String> queried = new HashSet<>();

    Frontier(KrpcMessage.NodeId target, List<RoutingTable.Entry> seed) {
        this.target = target;
        this.entries = new ArrayList<>(seed);
    }

    /** 弹出本批 alpha 个候选（其中已查询过的跳过，保持原批语义）；候选耗尽返回空表。 */
    List<RoutingTable.Entry> takeBatch() {
        List<RoutingTable.Entry> taken = new ArrayList<>(
                entries.subList(0, Math.min(ALPHA, entries.size())));
        entries.subList(0, taken.size()).clear();
        taken.removeIf(e -> !queried.add(e.id().hex()));
        return taken;
    }

    /** 加入响应中发现的更近候选（排序与截断交给 {@link #tighten()}）。 */
    void offer(RoutingTable.Entry entry) {
        entries.add(entry);
    }

    /** 轮末收紧：按与目标的异或距离排序并截断。 */
    void tighten() {
        entries.sort((a, b) -> RoutingTable.compareBytes(
                a.id().distanceTo(target), b.id().distanceTo(target)));
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size()).clear();
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }
}
