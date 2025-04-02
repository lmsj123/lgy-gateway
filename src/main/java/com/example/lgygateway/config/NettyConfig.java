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
    private int port; // netty监听的端口号
    private int times; // netty转发请求的重试次数
}
