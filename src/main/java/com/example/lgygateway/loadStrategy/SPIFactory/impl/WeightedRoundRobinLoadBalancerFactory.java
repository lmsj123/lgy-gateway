package com.example.lgygateway.loadStrategy.SPIFactory.impl;

import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.loadStrategy.SPIFactory.SPILoadStrategyFactory;
import com.example.lgygateway.loadStrategy.impl.WeightedRoundRobinLoadBalancer;

public class WeightedRoundRobinLoadBalancerFactory implements SPILoadStrategyFactory {
    @Override
    public LoadBalancerStrategy create() {
        return new WeightedRoundRobinLoadBalancer();
    }

    @Override
    public String getType() {
        return "WeightedRoundRobinLoadBalancer";
    }
}
