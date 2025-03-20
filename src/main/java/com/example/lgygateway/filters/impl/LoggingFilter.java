package com.example.lgygateway.filters.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import com.example.lgygateway.utils.Log;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;


public class LoggingFilter implements Filter {

    @Override
    public void filter(FullContext context, FilterChain chain,int index) {
        FullHttpRequest request = context.getRequest();
        Log.logger.info("Request: {} {}", request.method(), request.uri());

        // 继续执行下一个过滤器
        chain.doFilter(context,index);

        // 记录响应日志
        FullHttpResponse response = context.getResponse();
        if (response != null) {
            Log.logger.info("Response: {}", response.status());
        }
    }
}