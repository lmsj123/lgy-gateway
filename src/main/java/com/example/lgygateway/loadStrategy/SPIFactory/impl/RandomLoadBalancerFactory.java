package com.example.lgygateway.loadStrategy.SPIFactory.impl;

import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.loadStrategy.SPIFactory.SPILoadStrategyFactory;
import com.example.lgygateway.loadStrategy.impl.RandomLoadBalancer;

public class RandomLoadBalancerFactory implements SPILoadStrategyFactory {
    @Override
    public LoadBalancerStrategy create() {
        return new RandomLoadBalancer();
    }

    @Override
    public String getType() {
        return "RandomLoadBalancer";
    }
}
