package com.example.lgygateway.filters.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.curator.shaded.com.google.common.util.concurrent.RateLimiter;

public class RateLimitFilter implements Filter {
    private final RateLimiter rateLimiter = RateLimiter.create(100); // 每秒 100 个请求

    @Override
    public void filter(FullContext context, FilterChain chain,int index) {
        if (!rateLimiter.tryAcquire()) {
            // 限流，返回 429
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.TOO_MANY_REQUESTS);
            context.setResponse(response);
            return;
        }

        // 通过限流，继续执行下一个过滤器
        chain.doFilter(context,index);
    }
}