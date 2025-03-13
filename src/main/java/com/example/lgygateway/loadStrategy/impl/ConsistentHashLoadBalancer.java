package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component("consistentHashLoad")
@Lazy
public class ConsistentHashLoadBalancer implements LoadBalancerStrategy {
    private final SortedMap<Integer, Instance> circle = new TreeMap<>();
    private final AtomicInteger virtualNodes = new AtomicInteger(3); // 虚拟节点数

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        // 初始化哈希环
        if (circle.isEmpty()) {
            for (Instance instance : instances) {
                addInstanceToCircle(instance);
            }
        }

        // 根据请求的哈希值选择实例
        String requestKey = generateRequestKey(); // 生成请求的唯一标识
        int hash = hash(requestKey);
        SortedMap<Integer, Instance> tailMap = circle.tailMap(hash);
        int nodeHash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        return circle.get(nodeHash);
    }

    private void addInstanceToCircle(Instance instance) {
        for (int i = 0; i < virtualNodes.get(); i++) {
            String virtualNodeName = instance.getIp() + ":" + instance.getPort() + "#" + i;
            int hash = hash(virtualNodeName);
            circle.put(hash, instance);
        }
    }

    private String generateRequestKey() {
        // 生成请求的唯一标识（可以根据实际需求实现）
        return String.valueOf(System.currentTimeMillis());
    }

    private int hash(String key) {
        // 简单的哈希函数（可以使用更复杂的算法，如 MD5、MurmurHash）
        return key.hashCode();
    }
}