package com.example.lgygateway.filters.SPIFactory.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.SPIFactory.SPIFilterFactory;
import com.example.lgygateway.filters.impl.AuthFilter;

public class AuthFilterFactory implements SPIFilterFactory {
    @Override
    public Filter create() {
        return new AuthFilter();
    }

    @Override
    public String getType() {
        return "AuthFilter";
    }
}
