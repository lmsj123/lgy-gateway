package com.example.lgygateway.filters.models;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.Data;

@Data
public class FullContext {
    private FullHttpRequest request;
    private FullHttpResponse response;
 }
