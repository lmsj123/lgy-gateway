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
        //  这里存在当拿到实例集群时 某一个实例下线 如果currentInt 为 最后索引 这就会导致索引越界 所以currentIndex也需要取余
        int currentIndex = index.getAndUpdate(i -> (i + 1) % instances.size());
        return instances.get(currentIndex % instances.size());
    }

    @Override
    protected RoundRobinLoadBalancer clone() throws CloneNotSupportedException {
        RoundRobinLoadBalancer clone = (RoundRobinLoadBalancer) super.clone();
        clone.index = new AtomicInteger(0);
        return clone;
    }


}