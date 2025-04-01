package com.example.lgygateway.netty.testSplit.model;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class RequestContext {
    private ChannelHandlerContext frontendCtx;
    // 存储原始请求
    private FullHttpRequest originalRequest;
    // 存储剩余重试次数
    private int remainingRetries;
    // 存储长连接信息
    private boolean keepAlive;
    // 存储过期时间
    public long lastAccessTime;
}