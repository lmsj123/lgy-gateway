package com.example.lgygateway.loadStrategy;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ServiceLoader;

@Getter
@Component
public class LoadServer {
    private LoadBalancerStrategy loadBalancerStrategy;
    @PostConstruct
    public void init() {
        ServiceLoader<LoadBalancerStrategy> load = ServiceLoader.load(LoadBalancerStrategy.class);
        for (LoadBalancerStrategy strategy : load) {
            loadBalancerStrategy = strategy;
        }
    }
}
