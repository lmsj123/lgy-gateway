package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@ConfigurationProperties(prefix = "nacos")
@Data
@Lazy
public class NacosConfig {
    private String ip;
    private String port;
    private String dataId;
    private String group;
}
