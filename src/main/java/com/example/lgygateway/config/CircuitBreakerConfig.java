package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@ConfigurationProperties(prefix = "circuit-breaker")
@Data
@Lazy
public class CircuitBreakerConfig {
    private int failureThreshold;      // 失败率阈值（百分比）
    private int minRequestThreshold;   // 最小请求数（低于此值不触发熔断）
    private long openTimeoutMs;      // 熔断持续时间
    private int halfOpenPermits;// 半开状态允许的试探请求数
    private int halfOpenSuccessThreshold;// 半开状态成功阈值
    private int counterResetThreshold;// 计数器自动重置阈值
}
