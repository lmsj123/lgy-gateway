package com.example.lgygateway.filters;

import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.model.filter.FullContext;

public interface Filter {
    void filter(FullContext context, FilterChain filterChain,int index);
}
