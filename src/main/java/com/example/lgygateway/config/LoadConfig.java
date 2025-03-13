package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@Data
@ConfigurationProperties(prefix = "load")
@Lazy
public class LoadConfig {
    private String loadStrategy;
}
