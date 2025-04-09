package com.example.lgygateway.netty.testSplit.manager;

import com.example.lgygateway.config.LimitConfig;
import com.example.lgygateway.limit.SlidingWindowCounter;
import com.example.lgygateway.limit.TokenBucket;
import com.example.lgygateway.netty.testSplit.handler.ErrorHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.TOO_MANY_REQUESTS;

@Component
public class LimitManager {
    @Autowired
    private LimitConfig limitConfig;
    @Autowired
    private ErrorHandler errorHandler;
    // 用户令牌桶相关(用户限流体系)
    private final ConcurrentHashMap<String, TokenBucket> userBucketMap = new ConcurrentHashMap<>();
    // 用户令牌桶相关(游客限流体系)
    private final ConcurrentHashMap<String, TokenBucket> noUserBucketMap = new ConcurrentHashMap<>();
    // 服务令牌桶相关(服务限流体系)
    private final ConcurrentHashMap<InetSocketAddress, TokenBucket> serviceBucketMap = new ConcurrentHashMap<>();
    Logger logger = LoggerFactory.getLogger(LimitManager.class);
    // 滑动窗口限流(全局限流体系)
    private SlidingWindowCounter counter;
    @PostConstruct
    public void init() {
        counter = new SlidingWindowCounter(limitConfig.getWindowSizeSec(), limitConfig.getSliceCount());
    }

    // 全局限流和用户级限流的校验
    public boolean limitByGlobalAndUser(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!counter.allowRequest(limitConfig.getMaxQps())) {
            logger.info("全局限流体系（滑动窗口）提示请求被限流");
            errorHandler.sendErrorResponse(ctx, TOO_MANY_REQUESTS);
            return true;
        }
        String userId = extractUserId(request);
        if (userId.isEmpty()) {
            TokenBucket userTokenBucket = userBucketMap.computeIfAbsent(userId, k -> new TokenBucket(limitConfig.getUserMaxBurst(), limitConfig.getUserTokenRefillRate()));
            if (!userTokenBucket.tryAcquire(1)) {
                logger.info("该用户{}被限流", userId);
                errorHandler.sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        } else {
            TokenBucket noUserTokenBucket = noUserBucketMap.computeIfAbsent("noUser", k -> new TokenBucket(limitConfig.getNoUserMaxBurst(), limitConfig.getNoUserTokenRefillRate()));
            if (!noUserTokenBucket.tryAcquire(1)) {
                logger.info("该游客被限流");
                errorHandler.sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        }
        return false;
    }

    // 从请求中获取userId
    private String extractUserId(FullHttpRequest request) {
        return "1";
    }

    // 服务限流
    public boolean limitByService(URI uri, FullHttpRequest request, ChannelHandlerContext ctx) {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
        TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(limitConfig.getServiceMaxBurst(), limitConfig.getServiceTokenRefillRate()));
        if (!tokenBucket.tryAcquire(1)) {
            logger.info("该 {}:{} 服务实例被限流 释放请求连接", uri.getHost(), uri.getPort());
            ReferenceCountUtil.safeRelease(request);
            errorHandler.sendErrorResponse(ctx, TOO_MANY_REQUESTS);
            return false;
        }
        return true;
    }
}
