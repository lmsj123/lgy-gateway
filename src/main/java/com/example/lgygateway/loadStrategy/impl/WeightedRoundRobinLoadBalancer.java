package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
@Component("weightedRoundRobinLoadBalancer")
@Lazy
public class WeightedRoundRobinLoadBalancer implements LoadBalancerStrategy {
    private final AtomicInteger currentIndex = new AtomicInteger(-1); // 当前索引
    private final AtomicInteger currentWeight = new AtomicInteger(0); // 当前权重

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances.isEmpty()) {
            return null;
        }
        int maxWeight = (int)instances.stream().mapToDouble(Instance::getWeight).max().orElse(0);
        int gcdWeight = calculateGCD(instances);

        while (true) {
            int currentIdx = currentIndex.updateAndGet(idx -> (idx + 1) % instances.size());
            if (currentIdx == 0) {
                int newWeight = currentWeight.updateAndGet(w -> w - gcdWeight);
                if (newWeight <= 0) {
                    newWeight = maxWeight;
                    currentWeight.set(newWeight);
                }
            }

            Instance instance = instances.get(currentIdx);
            if (instance.getWeight() >= currentWeight.get()) {
                return instance;
            }
        }
    }

    private int calculateGCD(List<Instance> instances) {
        int gcd = 0;
        for (Instance instance : instances) {
            gcd = gcd(gcd, (int)instance.getWeight());
        }
        return gcd;
    }

    private int gcd(int a, int b) {
        return (b == 0) ? a : gcd(b, a % b);
    }
}