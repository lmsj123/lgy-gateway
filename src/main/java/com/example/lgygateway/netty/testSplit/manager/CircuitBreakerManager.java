package com.example.lgygateway.netty.testSplit.manager;

import com.example.lgygateway.circuitBreaker.CircuitBreaker;
import com.example.lgygateway.config.GrayCircuitBreakerConfig;
import com.example.lgygateway.config.NormalCircuitBreakerConfig;
import com.example.lgygateway.netty.testSplit.handler.ErrorHandler;
import com.example.lgygateway.netty.testSplit.model.RequestContext;
import com.example.lgygateway.utils.Log;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;

@Component
public class CircuitBreakerManager {
    @Autowired
    private ErrorHandler errorHandler;
    Logger logger = LoggerFactory.getLogger(CircuitBreakerManager.class);
    @Autowired
    private NormalCircuitBreakerConfig circuitBreakerConfig;
    @Autowired
    private GrayCircuitBreakerConfig grayCircuitBreakerConfig;
    // 熔断器存储
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    // 熔断器记录成功与失败的响应
    public void circuitBreakerRecordMes(RequestContext context, FullHttpResponse backendResponse) throws URISyntaxException {
        URI uri = new URI(context.getOriginalRequest().uri());
        String circuitBreakerKey = context.isGray() ? uri.getHost() + ":" + uri.getPort() + "-gray" : uri.getHost() + uri.getPort() + "-normal";
        CircuitBreaker circuitBreaker = circuitBreakers.get(circuitBreakerKey);
        if (circuitBreaker == null) {
            circuitBreaker = context.isGray() ? new CircuitBreaker(grayCircuitBreakerConfig) : new CircuitBreaker(circuitBreakerConfig);
            CircuitBreaker existing = circuitBreakers.putIfAbsent(circuitBreakerKey, circuitBreaker);
            if (existing != null) {
                circuitBreaker = existing;
            }
        }
        if (backendResponse.status().code() >= 500) {
            Log.logger.info("熔断器记录失败次数");
            circuitBreaker.recordFailure();
        } else {
            Log.logger.info("熔断器记录成功次数");
            circuitBreaker.recordSuccess();
        }
    }

    public CircuitBreaker getCircuitBreaker(String circuitBreakerKey,String isGray, FullHttpRequest request, ChannelHandlerContext ctx) {
        CircuitBreaker circuitBreaker = isGray.equals("true") ? circuitBreakers.computeIfAbsent(
                circuitBreakerKey,
                k -> new CircuitBreaker(grayCircuitBreakerConfig)
        ) : circuitBreakers.computeIfAbsent(
                circuitBreakerKey,
                k -> new CircuitBreaker(circuitBreakerConfig)
        );
        // 检查是否允许请求
        if (!circuitBreaker.allowRequest()) {
            logger.info("熔断器发挥降级作用，释放相关请求");
            ReferenceCountUtil.safeRelease(request);
            errorHandler.sendErrorResponse(ctx, SERVICE_UNAVAILABLE);
            return null;
        }
        return circuitBreaker;
    }
}
