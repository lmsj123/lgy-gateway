package com.example.lgygateway.filters.SPIFactory.impl;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.SPIFactory.SPIFilterFactory;
import com.example.lgygateway.filters.impl.LoggingFilter;

public class LoggingFilterFactory implements SPIFilterFactory {
    @Override
    public Filter create() {
        return new LoggingFilter();
    }

    @Override
    public String getType() {
        return "LoggingFilter";
    }
}
