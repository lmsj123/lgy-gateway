package com.example.lgygateway.loadStrategy;

import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.List;

public interface LoadBalancerStrategy{
    Instance selectInstance(List<Instance> instances);
}
