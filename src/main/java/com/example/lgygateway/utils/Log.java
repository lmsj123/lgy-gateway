package com.example.lgygateway.utils;

import com.example.lgygateway.filters.impl.LoggingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Log {
    public static final Logger logger = LoggerFactory.getLogger(LoggingFilter.class);

    public static void main(String[] args) {
        logger.info("Hello World");
    }

}
