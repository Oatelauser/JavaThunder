/**
 * 生产级 Tracker：HTTP announce（BEP 3/23）+ UDP announce（BEP 15）+ scrape
 * （BEP 48）+ 白名单 + /stats、/metrics 可观测端点；固定端口、Peer 过期清理、
 * stopped 摘除、多 swarm 并发。{@link io.github.oatelauser.thunder.tracker.TrackerServer}
 * 为生产外观（0.0.0.0 / 默认 6881 / interval 1800s），
 * {@link io.github.oatelauser.thunder.tracker.EmbeddedTracker} 为内嵌测试形态
 * （回环 / interval 2s，testkit 场景零迁移），
 * {@link io.github.oatelauser.thunder.tracker.TrackerMain} 为可执行 jar 入口。
 */
@NullMarked
package io.github.oatelauser.thunder.tracker;

import org.jspecify.annotations.NullMarked;
