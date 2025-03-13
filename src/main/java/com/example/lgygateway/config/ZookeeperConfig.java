package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Data
@Lazy
@Configuration
@ConfigurationProperties(prefix = "zookeeper")
public class ZookeeperConfig {
    private String ip;
    private String port;
}
