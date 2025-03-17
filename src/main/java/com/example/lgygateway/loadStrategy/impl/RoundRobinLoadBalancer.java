package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
@Component("roundRobinLoadBalancer")
@Lazy
//轮询
public class RoundRobinLoadBalancer implements LoadBalancerStrategy {
    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        int currentIndex = index.getAndUpdate(i -> (i + 1) % instances.size());
        return instances.get(currentIndex);
    }
}