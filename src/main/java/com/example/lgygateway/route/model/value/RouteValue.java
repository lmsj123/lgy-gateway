package com.example.lgygateway.route.model.value;

import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import lombok.Data;

@Data
public class RouteValue {
    private String method;
    private FilterChain filterChain;
    private LoadBalancerStrategy loadBalancerStrategy;
}
