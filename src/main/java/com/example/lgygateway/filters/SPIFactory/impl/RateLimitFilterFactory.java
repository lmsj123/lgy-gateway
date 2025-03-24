package com.example.lgygateway.filters.SPIFactory.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.SPIFactory.SPIFilterFactory;
import com.example.lgygateway.filters.impl.RateLimitFilter;

public class RateLimitFilterFactory implements SPIFilterFactory {
    @Override
    public Filter create() {
        return new RateLimitFilter();
    }

    @Override
    public String getType() {
        return "RateLimitFilter";
    }
}
