package com.example.thunder.config;

import io.github.oatelauser.thunder.api.TorrentClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接入姿势一 & 二（完整说明见 docs/MANUAL.md §6.6）：
 *
 * <ol>
 *   <li><b>TorrentClient 是重型资源</b>（监听端口 / 全局限速 / 事件线程），整个应用一个
 *       单例 Bean——不要按请求或按任务创建。</li>
 *   <li><b>回调线程契约</b>：TaskListener 回调默认跑在库内单线程上；要自定义时注入
 *       专用 Executor，<b>不要</b>借 Tomcat 工作线程或公共 ForkJoinPool。</li>
 *   <li><b>优雅停机</b>：destroyMethod = "close" 在应用关闭时调用 client.close()
 *       （AutoCloseable），停止全部任务并释放端口/线程。</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ThunderProperties.class)
public class ThunderConfiguration {

    /**
     * 库事件回调的专用线程池（2 线程足够：回调轻、不阻塞）。
     * Bean 销毁顺序：依赖方先销毁——torrentClient 先 close，本池后 shutdown。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService thunderListenerExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "javathunder-listener-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 单例引擎 Bean；应用关闭 = client.close()（停任务、释放资源）。 */
    @Bean(destroyMethod = "close")
    public TorrentClient torrentClient(ThunderProperties properties,
            @Qualifier("thunderListenerExecutor") ExecutorService listenerExecutor) throws IOException {
        return TorrentClient.builder()
                .listenPort(properties.listenPort())
                .maxConcurrentTasks(properties.maxConcurrentTasks())
                .listenerExecutor(listenerExecutor)
                .build();
    }
}
