package com.example.lgygateway.filters.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import com.example.lgygateway.utils.Log;
import io.netty.handler.codec.http.*;

public class AuthFilter implements Filter {
    @Override
    public void filter(FullContext context, FilterChain chain) {
        Log.logger.info("正在验证权限");
        FullHttpRequest request = context.getRequest();
        String token = request.headers().get("Authorization");
        if (token == null || !isValidToken(token)) {
            // 认证失败，返回 401
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            context.setResponse(response);
            return;
        }

        // 认证通过，继续执行下一个过滤器
        chain.doFilter(context);
        Log.logger.info("权限验证通过");
    }

    private boolean isValidToken(String token) {
        // 实现 Token 验证逻辑
        return true;
    }
}