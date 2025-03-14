package com.example.lgygateway.filters;

import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;

public interface Filter {
    void filter(FullContext context, FilterChain filterChain);
}
