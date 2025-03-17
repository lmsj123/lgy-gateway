package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@ConfigurationProperties(prefix = "netty")
@Data
@Configuration
@Lazy
public class NettyConfig {
    private int port;
    private int times;
    private int maxQps;
    private int maxBurst;
    private int tokenRefillRate;
}
