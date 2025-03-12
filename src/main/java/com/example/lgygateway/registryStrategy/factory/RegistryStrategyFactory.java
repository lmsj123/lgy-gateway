package com.example.lgygateway.registryStrategy.factory;

import com.example.lgygateway.config.GatewayConfig;
import com.example.lgygateway.config.RegistryConfig;
import com.example.lgygateway.registryStrategy.RegistryStrategy;
import com.example.lgygateway.registryStrategy.impl.NacosRegistryStrategy;
//import com.example.lgygateway.registryStrategy.impl.ZookeeperRegistryStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RegistryStrategyFactory {
    @Autowired
    private NacosRegistryStrategy nacosRegistryStrategy;
//    @Autowired
//    private ZookeeperRegistryStrategy zookeeperRegistryStrategy;
    @Autowired
    private RegistryConfig registryConfig;

    public RegistryStrategy getRegistryStrategy() {
        if ("nacos".equalsIgnoreCase(registryConfig.getType())) {
            return nacosRegistryStrategy;
//        } else if ("zookeeper".equalsIgnoreCase(registryConfig.getType())) {
//            return zookeeperRegistryStrategy;
//        } else {
        }else {
            throw new IllegalArgumentException("Unsupported registry type: " + registryConfig.getType());
        }
    }
}
