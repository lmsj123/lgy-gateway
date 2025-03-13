package com.example.lgygateway.registryStrategy.factory;

import com.example.lgygateway.config.RegistryConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.impl.NacosRegistryStrategy;
import com.example.lgygateway.registryStrategy.impl.ZookeeperRegistryStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class RegistryStrategyFactory {
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private RegistryConfig registryConfig;

    private Registry registryStrategy;

    public Registry getRegistryStrategy() {
        if (registryStrategy != null) {
            return registryStrategy;
        }
        if ("nacos".equalsIgnoreCase(registryConfig.getType())) {
            registryStrategy = applicationContext.getBean(NacosRegistryStrategy.class);
            return registryStrategy;
        }else if ("zookeeper".equalsIgnoreCase(registryConfig.getType())) {
            registryStrategy = applicationContext.getBean(ZookeeperRegistryStrategy.class);
            return registryStrategy;
        } else {
            throw new IllegalArgumentException("Unsupported registry type: " + registryConfig.getType());
        }
    }
}
