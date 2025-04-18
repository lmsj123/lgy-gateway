package com.example.lgygateway.filters.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.model.filter.FullContext;
import org.apache.curator.shaded.com.google.common.util.concurrent.RateLimiter;

public class RateLimitFilter implements Filter {
    private final RateLimiter rateLimiter = RateLimiter.create(100); // 每秒 100 个请求

    @Override
    public void filter(FullContext context, FilterChain chain,int index) {
        // 通过限流，继续执行下一个过滤器
        chain.doFilter(context,index);
    }
}