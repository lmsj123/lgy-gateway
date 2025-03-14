package com.example.lgygateway.config;

import com.example.lgygateway.registryStrategy.impl.NacosRegistry;
import com.example.lgygateway.registryStrategy.impl.ZookeeperRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RegistryStrategyConfig {

    @Bean
    @ConditionalOnProperty(name = "registry.center.type", havingValue = "nacos")
    public NacosRegistry nacosRegistryStrategy() {
        return new NacosRegistry();
    }

    @Bean
    @ConditionalOnProperty(name = "registry.center.type", havingValue = "zookeeper")
    public ZookeeperRegistry zookeeperRegistryStrategy() {
        return new ZookeeperRegistry();
    }
}
