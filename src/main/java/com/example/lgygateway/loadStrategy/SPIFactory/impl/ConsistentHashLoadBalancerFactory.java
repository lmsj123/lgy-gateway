package com.example.lgygateway.loadStrategy.SPIFactory.impl;

import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.loadStrategy.SPIFactory.SPILoadStrategyFactory;
import com.example.lgygateway.loadStrategy.impl.ConsistentHashLoadBalancer;

public class ConsistentHashLoadBalancerFactory implements SPILoadStrategyFactory {
    @Override
    public LoadBalancerStrategy create() {
        return new ConsistentHashLoadBalancer();
    }

    @Override
    public String getType() {
        return "ConsistentHashLoadBalancer";
    }
}
