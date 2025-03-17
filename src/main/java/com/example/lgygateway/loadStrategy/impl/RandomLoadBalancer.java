package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
@Component("randomLoadBalancer")
@Lazy
//随机
public class RandomLoadBalancer implements LoadBalancerStrategy {
    private final Random random = new Random();

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        int index = random.nextInt(instances.size());
        return instances.get(index);
    }
}
