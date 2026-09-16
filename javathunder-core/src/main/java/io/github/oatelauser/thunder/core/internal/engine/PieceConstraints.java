package io.github.oatelauser.thunder.core.internal.engine;

import java.util.Set;
import java.util.function.IntPredicate;

/**
 * 选件约束快照：组装中 / 待校验件集合、组装器占用与并发件上限（内存上限），
 * 以及"该件仍有缺失块"判定（组装器视角）。字段在调用点以会话实时状态构造，
 * 不做内部一致性保证——与选件遍历同一弱一致级别。
 */
record PieceConstraints(Set<Integer> activePieces,
                        Set<Integer> verifyingPieces,
                        int assemblingCount,
                        int maxActivePieces,
                        IntPredicate hasMissingBlock) {
}
