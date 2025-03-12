package com.example.lgygateway.registryStrategy;

import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.List;
import java.util.Map;

public interface RegistryStrategy {
    //更新路由规则 用于后续自动更新
    void updateRouteRules();
    //得到对应的路由规则
    Map<String, List<Instance>> getRouteRules();
}