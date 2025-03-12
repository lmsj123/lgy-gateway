package com.example.lgygateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nacos")
@Data
public class NacosConfig {
  String  ip;
  String  port;
  String  dataId;
  String  group;
}
