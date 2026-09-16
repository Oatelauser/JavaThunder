/**
 * 生产级 Tracker：HTTP announce（BEP 3/23）+ UDP announce（BEP 15）+ scrape
 * （BEP 48）+ 白名单 + /stats、/metrics 可观测端点；固定端口、Peer 过期清理、
 * stopped 摘除、多 swarm 并发。{@link io.github.oatelauser.thunder.tracker.EmbeddedTracker}
 * 是唯一实现（内嵌测试形态默认回环 / interval 2s；生产形态用它绑通配地址 / 6881 / 1800s），
 * {@link io.github.oatelauser.thunder.tracker.TrackerMain} 为可执行 jar 入口。
 */
@NullMarked
package io.github.oatelauser.thunder.tracker;

import org.jspecify.annotations.NullMarked;
