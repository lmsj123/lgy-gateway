package com.example.lgygateway.loadStrategy;

import com.example.lgygateway.config.GatewayConfig;
import com.example.lgygateway.config.LoadConfig;
import com.example.lgygateway.loadStrategy.impl.RoundRobinLoadBalancer;
import com.example.lgygateway.loadStrategy.impl.WeightedRoundRobinLoadBalancer;
import com.example.lgygateway.registryStrategy.RegistryStrategy;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Data
public class LoadServer {
    @Autowired
    private LoadConfig loadConfig;
    private LoadBalancerStrategy loadBalancerStrategy;
    @PostConstruct
    public void init() {
        if (loadConfig.getLoadStrategy().equals("round_robin")){
            loadBalancerStrategy = new RoundRobinLoadBalancer();
        }else if (loadConfig.getLoadStrategy().equals("weighted_round_robin")){
            loadBalancerStrategy = new WeightedRoundRobinLoadBalancer();
        }else {
            throw new IllegalArgumentException("Unsupported load_strategy: " + loadConfig.getLoadStrategy());
        }
    }
}
