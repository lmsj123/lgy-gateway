package com.example.lgygateway.filters.SPIFactory;

import com.example.lgygateway.filters.Filter;

public interface SPIFilterFactory {
    Filter create();
    String getType();
}
