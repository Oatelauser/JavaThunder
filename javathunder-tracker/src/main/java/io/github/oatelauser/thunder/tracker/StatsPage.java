package io.github.oatelauser.thunder.tracker;

import java.util.Map;
import java.util.Set;

/**
 * /stats 人类可读 HTML 渲染（纯函数：状态进，页面出）。
 */
final class StatsPage {

    private StatsPage() {
    }

    static String render(SwarmRegistry registry, TrackerMetrics metrics,
            int udpPort, boolean whitelistEnabled) {
        Map<String, EmbeddedTracker.SwarmStats> perSwarm = registry.stats();
        Set<String> hexes = registry.knownHashes();
        long totalSeeders = 0;
        long totalLeechers = 0;
        for (EmbeddedTracker.SwarmStats swarm : perSwarm.values()) {
            totalSeeders += swarm.seeders();
            totalLeechers += swarm.leechers();
        }
        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">")
                .append("<title>javathunder-tracker stats</title>\n")
                .append("<style>body{font-family:system-ui,sans-serif;margin:2rem}")
                .append("table{border-collapse:collapse;margin-bottom:1.5rem}")
                .append("td,th{border:1px solid #ccc;padding:.25rem .9rem;text-align:left}")
                .append("th{background:#f4f4f4}code{font-size:.9em}</style>\n")
                .append("</head>\n<body>\n<h1>javathunder-tracker</h1>\n");
        html.append("<table>\n");
        row(html, "active swarms", perSwarm.size());
        row(html, "peers (seed / leech)", totalSeeders + " / " + totalLeechers);
        row(html, "downloads (completed)", registry.totalDownloads());
        row(html, "announces (http / udp)",
                metrics.httpAnnounces() + " / " + metrics.udpAnnounces());
        row(html, "scrapes", metrics.scrapes());
        row(html, "udp", udpPort < 0 ? "disabled" : "port " + udpPort);
        row(html, "whitelist", whitelistEnabled ? "enabled" : "disabled");
        html.append("</table>\n");
        html.append("<table>\n<tr><th>info-hash</th><th>seeders</th><th>leechers</th>")
                .append("<th>peers</th><th>downloads</th></tr>\n");
        for (String hex : hexes) {
            EmbeddedTracker.SwarmStats swarm = perSwarm.get(hex);
            Map<String, Long> counts = registry.scrapeEntry(hex);
            html.append("<tr><td><code>").append(hex).append("</code></td><td>")
                    .append(swarm == null ? 0 : swarm.seeders()).append("</td><td>")
                    .append(swarm == null ? 0 : swarm.leechers()).append("</td><td>")
                    .append(swarm == null ? 0 : swarm.total()).append("</td><td>")
                    .append(counts.get("downloaded")).append("</td></tr>\n");
        }
        html.append("</table>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void row(StringBuilder html, String key, Object value) {
        html.append("<tr><th>").append(key).append("</th><td>").append(value).append("</td></tr>\n");
    }
}
