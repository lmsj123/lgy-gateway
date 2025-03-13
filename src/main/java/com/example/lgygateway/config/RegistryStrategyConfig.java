package com.example.lgygateway.config;

import com.example.lgygateway.registryStrategy.impl.NacosRegistryStrategy;
import com.example.lgygateway.registryStrategy.impl.ZookeeperRegistryStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RegistryStrategyConfig {

    @Bean
    @ConditionalOnProperty(name = "registry.center.type", havingValue = "nacos")
    public NacosRegistryStrategy nacosRegistryStrategy() {
        return new NacosRegistryStrategy();
    }

    @Bean
    @ConditionalOnProperty(name = "registry.center.type", havingValue = "zookeeper")
    public ZookeeperRegistryStrategy zookeeperRegistryStrategy() {
        return new ZookeeperRegistryStrategy();
    }
}
