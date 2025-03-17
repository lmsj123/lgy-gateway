package com.example.lgygateway.loadStrategy.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import org.apache.curator.shaded.com.google.common.hash.Hashing;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

@Component("consistentHashLoad")
@Lazy
public class ConsistentHashLoadBalancer implements LoadBalancerStrategy {
    private static final int VIRTUAL_NODES_PER_INSTANCE = 160;
    private final ConcurrentNavigableMap<Integer, Instance> circle = new ConcurrentSkipListMap<>();
    private volatile List<Instance> lastKnownInstances = Collections.emptyList();

    @Override
    public Instance selectInstance(List<Instance> instances) {
        if (instances.isEmpty()) {
            return null;
        }

        // 检测实例变化并重建环
        if (hasInstanceListChanged(instances)) {
            rebuildCircle(instances);
        }

        // 生成请求键并哈希
        String requestKey = generateRequestKey();
        int hash = hash(requestKey);

        // 查找最近的节点
        ConcurrentNavigableMap<Integer, Instance> tailMap = circle.tailMap(hash);
        int nodeHash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        return circle.get(nodeHash);
    }

    private boolean hasInstanceListChanged(List<Instance> currentInstances) {
        // 通过实例ID集合判断是否变化
        Set<String> currentIds = currentInstances.stream()
                .map(Instance::getInstanceId)
                .collect(Collectors.toSet());
        Set<String> circleIds = circle.values().stream()
                .map(Instance::getInstanceId)
                .collect(Collectors.toSet());
        return !currentIds.equals(circleIds);
    }

    private void rebuildCircle(List<Instance> instances) {
        ConcurrentNavigableMap<Integer, Instance> newCircle = new ConcurrentSkipListMap<>();
        for (Instance instance : instances) {
            for (int i = 0; i < VIRTUAL_NODES_PER_INSTANCE; i++) {
                String virtualNode = instance.getInstanceId() + "#" + i;
                int hash = hash(virtualNode);
                newCircle.put(hash, instance);
            }
        }
        circle.clear();
        circle.putAll(newCircle);
        lastKnownInstances = new ArrayList<>(instances);
    }

    private String generateRequestKey() {
        // 实际项目应从请求上下文中获取特征（如用户ID）
        return UUID.randomUUID().toString();
    }

    private int hash(String key) {
        // 使用Guava MurmurHash32
        return Hashing.murmur3_32().hashUnencodedChars(key).asInt() & 0x7FFFFFFF;
    }
}