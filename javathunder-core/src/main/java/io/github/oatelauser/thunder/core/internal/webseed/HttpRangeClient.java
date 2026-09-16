package io.github.oatelauser.thunder.core.internal.webseed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * WebSeed HTTP 兜底源客户端（BEP 19）：对拼接字节流按区间拉取（{@code Range: bytes=a-b}）。
 *
 * <p>源级策略内聚于此——多源轮询、失败指数退避（2s 起逐次翻倍、上限 60s、成功即复位）、
 * 连续失败 2 次熔断该源；两类硬失败立即熔断：HTTP 200（服务器忽略 Range 回全量——
 * 大文件场景不可接受，v1 不做全量截取）与 416（区间越界 = 源数据与种子不匹配的强信号）。
 * 全部源熔断后 {@link #fetchPiece} 抛 {@link IOException}，调用方据此停用 HTTP 通道。
 *
 * <p>时钟经 {@link LongSupplier} 注入（退避判定可测，不必真实等待）。
 */
public final class HttpRangeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRangeClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final long INITIAL_BACKOFF_MILLIS = 2_000;
    private static final long MAX_BACKOFF_MILLIS = 60_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Source[] sources;
    private final long totalLength;
    private final LongSupplier clock;
    private int nextSourceIndex;

    /** 生产构造（引擎侧使用；时钟取系统毫秒）。 */
    public HttpRangeClient(List<String> baseUrls, long totalLength) {
        this(baseUrls, totalLength, System::currentTimeMillis);
    }

    /** 测试构造：注入时钟以推进退避窗口。 */
    HttpRangeClient(List<String> baseUrls, long totalLength, LongSupplier clock) {
        this.sources = baseUrls.stream().map(Source::new).toArray(Source[]::new);
        this.totalLength = totalLength;
        this.clock = clock;
    }

    /**
     * 拉取拼接流第 {@code pieceIndex} 件的全部字节（末件按剩余长度截断）。
     *
     * @throws IOException 全部源已熔断/退避中，或本轮尝试的每个源都失败
     */
    public byte[] fetchPiece(int pieceIndex, long pieceLength) throws IOException {
        long from = pieceIndex * pieceLength;
        long to = Math.min(from + pieceLength, totalLength) - 1;
        if (pieceIndex < 0 || from > to || to >= totalLength) {
            throw new IllegalArgumentException("piece " + pieceIndex + " out of stream bounds");
        }
        IOException last = new IOException("no web seed source available (all disabled or backing off)");
        int start = nextSourceIndex;
        for (int attempt = 0; attempt < sources.length; attempt++) {
            int index = (start + attempt) % sources.length;
            Source source = sources[index];
            if (source.disabled || clock.getAsLong() < source.retryAtMillis) {
                continue;
            }
            try {
                byte[] body = request(source, from, to);
                source.consecutiveFailures = 0;
                source.retryAtMillis = 0L;
                nextSourceIndex = (index + 1) % sources.length;
                return body;
            } catch (IOException e) {
                last = e;
                onSourceFailure(source);
            }
        }
        throw last;
    }

    /** 全部源已熔断（观测/测试用；退避中的源不算——稍后仍可能恢复）。 */
    public boolean allSourcesDisabled() {
        for (Source source : sources) {
            if (!source.disabled) {
                return false;
            }
        }
        return true;
    }

    private byte[] request(Source source, long from, long to) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.url))
                .timeout(REQUEST_TIMEOUT)
                .header("Range", "bytes=" + from + "-" + to)
                .header("User-Agent", "JavaThunder/0.5")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("web seed request interrupted", e);
        }
        if (response.statusCode() == 200) {
            // 服务器忽略 Range 回全量：大文件场景不可接受，视为不支持 Range，立即熔断
            disable(source, "HTTP 200 — Range not honored (full-body responses unsupported)");
            throw new IOException(source.url + ": expected 206, got 200 (Range ignored)");
        }
        if (response.statusCode() == 416) {
            disable(source, "HTTP 416 — source data does not match torrent bounds");
            throw new IOException(source.url + ": 416 range not satisfiable");
        }
        if (response.statusCode() != 206) {
            throw new IOException(source.url + ": expected 206, got " + response.statusCode());
        }
        byte[] body = response.body();
        long expected = to - from + 1;
        if (body.length != expected) {
            throw new IOException(source.url + ": short body " + body.length + " bytes, expected " + expected);
        }
        return body;
    }

    /** 瞬态失败：退避后仍可再试；连续达上限则熔断。 */
    private void onSourceFailure(Source source) {
        source.consecutiveFailures++;
        if (source.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            disable(source, source.consecutiveFailures + " consecutive failures");
            return;
        }
        long backoff = Math.min(
                INITIAL_BACKOFF_MILLIS << (source.consecutiveFailures - 1), MAX_BACKOFF_MILLIS);
        source.retryAtMillis = clock.getAsLong() + backoff;
    }

    private void disable(Source source, String reason) {
        if (!source.disabled) {
            source.disabled = true;
            log.info("web seed source disabled: {} ({})", source.url, reason);
        }
    }

    /** 单个 HTTP 源的健康状态（熔断/退避记账）。 */
    private static final class Source {
        final String url;
        int consecutiveFailures;
        boolean disabled;
        long retryAtMillis;

        Source(String url) {
            this.url = url;
        }
    }
}
