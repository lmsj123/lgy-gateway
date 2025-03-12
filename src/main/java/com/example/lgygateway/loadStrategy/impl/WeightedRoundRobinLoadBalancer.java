package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class WeightedRoundRobinLoadBalancer implements LoadBalancerStrategy {
    private AtomicInteger index = new AtomicInteger(0);
    private int totalWeight = 0;
    private int gcdWeight = 0;
    private int maxWeight = 0;
    private int serverCount = 0;

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        if (totalWeight == 0) {
            for (Instance instance : instances) {
                int weight = (int) instance.getWeight();
                totalWeight += weight;
                maxWeight = Math.max(maxWeight, weight);
            }
            gcdWeight = gcd(instances);
            serverCount = instances.size();
        }
        while (true) {
            index.compareAndSet(index.get(), (index.get() + 1) % serverCount);
            if (index.get() == 0) {
                index.set(maxWeight);
                if (--gcdWeight <= 0) {
                    gcdWeight = gcd(instances);
                    if (gcdWeight <= 0) {
                        break;
                    }
                }
            }
            int i = index.get();
            if (instances.get(i).getWeight() >= i) {
                return instances.get(i);
            }
        }
        return null;
    }

    private int gcd(List<Instance> instances) {
        int result = 0;
        for (Instance instance : instances) {
            result = gcd(result, (int) instance.getWeight());
        }
        return result;
    }

    private int gcd(int a, int b) {
        if (b == 0) {
            return a;
        } else {
            return gcd(b, a % b);
        }
    }
}