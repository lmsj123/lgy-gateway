package com.example.lgygateway.registryStrategy;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.route.model.value.RouteValue;

import java.util.List;
import java.util.Map;

public interface Registry {
    //得到对应的路由规则
    Map<String, List<Instance>> getRouteRules();
    //得到对应的路由属性
    Map<String, RouteValue> getRouteValues();
}