package io.github.oatelauser.thunder.tracker;

import java.util.Map;

/** /metrics Prometheus 文本格式（0.0.4）渲染（纯函数）。 */
final class MetricsPage {

    private MetricsPage() {
    }

    static String render(SwarmRegistry registry, TrackerMetrics metrics) {
        Map<String, EmbeddedTracker.SwarmStats> perSwarm = registry.stats();
        StringBuilder text = new StringBuilder(2048);
        text.append("# HELP javathunder_tracker_swarm_peers Peers currently registered per swarm.\n")
                .append("# TYPE javathunder_tracker_swarm_peers gauge\n");
        for (Map.Entry<String, EmbeddedTracker.SwarmStats> entry : perSwarm.entrySet()) {
            text.append("javathunder_tracker_swarm_peers{role=\"seed\",info_hash=\"")
                    .append(entry.getKey()).append("\"} ").append(entry.getValue().seeders()).append('\n');
            text.append("javathunder_tracker_swarm_peers{role=\"leech\",info_hash=\"")
                    .append(entry.getKey()).append("\"} ").append(entry.getValue().leechers()).append('\n');
        }
        text.append("# HELP javathunder_tracker_swarm_downloads_total Completed downloads per swarm.\n")
                .append("# TYPE javathunder_tracker_swarm_downloads_total counter\n");
        for (String hex : registry.knownHashes()) {
            text.append("javathunder_tracker_swarm_downloads_total{info_hash=\"")
                    .append(hex).append("\"} ").append(registry.scrapeEntry(hex).get("downloaded"))
                    .append('\n');
        }
        text.append("# HELP javathunder_tracker_announces_total Announce requests processed, by transport.\n")
                .append("# TYPE javathunder_tracker_announces_total counter\n")
                .append("javathunder_tracker_announces_total{transport=\"http\"} ")
                .append(metrics.httpAnnounces()).append('\n')
                .append("javathunder_tracker_announces_total{transport=\"udp\"} ")
                .append(metrics.udpAnnounces()).append('\n')
                .append("# HELP javathunder_tracker_scrapes_total Scrape requests processed.\n")
                .append("# TYPE javathunder_tracker_scrapes_total counter\n")
                .append("javathunder_tracker_scrapes_total ").append(metrics.scrapes()).append('\n')
                .append("# HELP javathunder_tracker_active_swarms Swarms with at least one registered peer.\n")
                .append("# TYPE javathunder_tracker_active_swarms gauge\n")
                .append("javathunder_tracker_active_swarms ").append(perSwarm.size()).append('\n');
        return text.toString();
    }
}
