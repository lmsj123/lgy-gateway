package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@ConfigurationProperties(prefix = "pool")
@Data
@Lazy
public class ChannelPoolConfig {
    private long acquireTimeoutMillis; // 获取连接的超时时间
    private int maxConnections; // 连接池中允许的最大连接数
    private int maxPendingRequests; // 最大获取连接的请求数 超过了这个数量的额外请求将被拒绝或抛出异常
}
