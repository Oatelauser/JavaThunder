package com.example.thunder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 引擎全局配置（application.yml 的 javathunder.* 前缀）。
 *
 * <p>紧凑构造器兜底默认值：属性缺失时 int 绑定为 0，这里收敛回引擎默认（6881 / 3），
 * 使示例在无 yml 时也能启动。
 */
@ConfigurationProperties(prefix = "javathunder")
public record ThunderProperties(int listenPort, int maxConcurrentTasks) {

    public ThunderProperties {
        if (listenPort <= 0 || listenPort > 65535) {
            listenPort = 6881;
        }
        if (maxConcurrentTasks <= 0) {
            maxConcurrentTasks = 3;
        }
    }
}
