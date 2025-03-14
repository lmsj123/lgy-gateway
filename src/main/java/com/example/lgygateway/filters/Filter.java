package com.example.lgygateway.filters;

import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import io.netty.handler.codec.http.FullHttpRequest;

public interface Filter {
    void filter(FullContext context, FilterChain filterChain);
}
