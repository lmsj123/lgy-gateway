package com.example.lgygateway.model.route.routeValue;

import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.model.route.routeConfig.GrayStrategy;
import lombok.Data;

@Data
public class RouteValue {
    private String method;
    private FilterChain filterChain;
    private LoadBalancerStrategy loadBalancerStrategy;
    private GrayStrategy grayStrategy; // 新增灰度策略字段
}
