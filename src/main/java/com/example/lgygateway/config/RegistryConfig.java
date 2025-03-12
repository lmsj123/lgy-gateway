package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
@Configuration
@ConfigurationProperties(prefix = "registration.center")
@Data
public class RegistryConfig {
    private String type; // "nacos" or "zookeeper"
    private String serverAddr;
}
