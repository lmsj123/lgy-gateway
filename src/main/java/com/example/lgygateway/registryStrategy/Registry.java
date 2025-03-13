package com.example.lgygateway.registryStrategy;

import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public interface Registry {
    //得到对应的路由规则
    ConcurrentHashMap<String, List<Instance>> getRouteRules();
}