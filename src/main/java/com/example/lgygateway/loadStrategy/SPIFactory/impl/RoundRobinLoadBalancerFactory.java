package com.example.lgygateway.loadStrategy.SPIFactory.impl;

import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.loadStrategy.SPIFactory.SPILoadStrategyFactory;
import com.example.lgygateway.loadStrategy.impl.RoundRobinLoadBalancer;

public class RoundRobinLoadBalancerFactory implements SPILoadStrategyFactory {
    @Override
    public LoadBalancerStrategy create() {
        return new RoundRobinLoadBalancer();
    }

    @Override
    public String getType() {
        return "RoundRobinLoadBalancer";
    }
}
