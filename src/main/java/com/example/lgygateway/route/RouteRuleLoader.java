package com.example.lgygateway.route;

import com.example.lgygateway.netty.NettyHttpServer;
import com.example.lgygateway.registryStrategy.RegistryStrategy;
import com.example.lgygateway.registryStrategy.factory.RegistryStrategyFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RouteRuleLoader {
    @Autowired
    private RegistryStrategyFactory registryStrategyFactory;
    @Autowired
    private  NettyHttpServer nettyHttpServer;

    @PostConstruct
    public void start() throws InterruptedException {
        new Thread(() -> {
            try {
                nettyHttpServer.start(); // Start Netty server in a separate thread
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Scheduled(fixedRate = 60000) // Update every minute, adjust as needed
    public void updateRouteRules() {
        RegistryStrategy registryStrategy = registryStrategyFactory.getRegistryStrategy();
        registryStrategy.updateRouteRules(); // Update route rules from Nacos or Zookeeper
    }
}
