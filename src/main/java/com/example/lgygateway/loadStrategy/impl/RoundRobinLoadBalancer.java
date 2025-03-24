package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

//轮询
public class RoundRobinLoadBalancer implements LoadBalancerStrategy{
    private AtomicInteger index = new AtomicInteger(0);

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        int currentIndex = index.getAndUpdate(i -> (i + 1) % instances.size());
        return instances.get(currentIndex);
    }

    @Override
    protected RoundRobinLoadBalancer clone() throws CloneNotSupportedException {
        RoundRobinLoadBalancer clone = (RoundRobinLoadBalancer) super.clone();
        clone.index = new AtomicInteger(0);
        return clone;
    }


}