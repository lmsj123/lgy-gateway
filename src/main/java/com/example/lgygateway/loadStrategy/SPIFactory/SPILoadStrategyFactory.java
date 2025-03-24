package com.example.lgygateway.loadStrategy.SPIFactory;

import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
//通过工厂模式去隔离因为SPI机制导致的单例
public interface SPILoadStrategyFactory {
    LoadBalancerStrategy create();
    String getType();
}
