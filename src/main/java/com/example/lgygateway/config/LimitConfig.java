package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "limit")
@Data
public class LimitConfig {
    private int maxQps; // 滑动窗口接受请求的最大qps
    private int windowSizeSec; // 窗口总时长（秒）
    private int sliceCount; // 窗口分片数量
    private int serviceMaxBurst; // 服务令牌桶数量
    private int serviceTokenRefillRate; // 服务令牌桶每秒填充速率
    private int userMaxBurst; // 用户令牌桶数量
    private int userTokenRefillRate; // 用户令牌桶每秒填充速率
    private int noUserMaxBurst; // 游客令牌桶数量
    private int noUserTokenRefillRate; // 游客令牌桶每秒填充速率
}
