package com.example.lgygateway.netty.testSplit.manager;

import cn.hutool.json.JSONUtil;
import com.example.lgygateway.circuitBreaker.CircuitBreaker;
import com.example.lgygateway.config.CircuitBreakerConfig;
import com.example.lgygateway.netty.testSplit.model.RequestContext;
import com.example.lgygateway.utils.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class CircuitBreakerManager {
    @Autowired
    private RequestContextMapManager requestContextMapManager;
    private AttributeKey<String> REQUEST_ID_KEY;
    private ConcurrentHashMap<String, RequestContext> requestContextMap;

    @PostConstruct
    public void init() {
        this.REQUEST_ID_KEY = requestContextMapManager.getRequestIdKey();
        this.requestContextMap = requestContextMapManager.getRequestContextMap();
    }

    Logger logger = LoggerFactory.getLogger(CircuitBreakerManager.class);
    @Autowired
    private CircuitBreakerConfig circuitBreakerConfig;
    // 熔断器存储
    private final ConcurrentHashMap<InetSocketAddress, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    // 熔断器记录成功与失败的响应
    public void circuitBreakerRecordMes(RequestContext context, FullHttpResponse backendResponse) throws URISyntaxException {
        URI uri = new URI(context.getOriginalRequest().uri());
        InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
        CircuitBreaker circuitBreaker = circuitBreakers.get(inetSocketAddress);
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreaker(circuitBreakerConfig);
            CircuitBreaker existing = circuitBreakers.putIfAbsent(inetSocketAddress, circuitBreaker);
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

    public CircuitBreaker getCircuitBreaker(URI uri, FullHttpRequest request, ChannelHandlerContext ctx) {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
        logger.info("正在获取 {}:{} 的熔断器", uri.getHost(), uri.getPort());
        //高并发下多个线程可能同时执行 new CircuitBreaker，导致资源浪费或状态不一致。
        //若 TokenBucket 构造函数无副作用，可以接受短暂重复创建，但需确保 TokenBucket 自身线程安全
        //可考虑优化成双重锁
        CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(
                inetSocketAddress,
                k -> new CircuitBreaker(circuitBreakerConfig)
        );
        // 检查是否允许请求
        if (!circuitBreaker.allowRequest()) {
            logger.info("熔断器发挥降级作用，释放相关请求");
            ReferenceCountUtil.safeRelease(request);
            sendErrorResponse(ctx, SERVICE_UNAVAILABLE);
            return null;
        }
        return circuitBreaker;
    }

    // 发送失败响应给客户端
    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
        // 构建JSON错误信息对象
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("code", status.code());
        errorData.put("message", status.reasonPhrase());
        errorData.put("timestamp", System.currentTimeMillis());
        logger.warn("正在发送失败响应");
        String jsonResponse;
        try {
            jsonResponse = JSONUtil.toJsonStr(errorData); // 使用JSON库（如FastJSON/Gson）
        } catch (Exception e) {
            jsonResponse = "{\"error\":\"JSON序列化失败\"}";
            logger.error("JSON序列化异常", e);
        }
        String requestId = ctx.channel().attr(REQUEST_ID_KEY).get();
        // 创建响应对象
        ByteBuf content = Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                status,
                content  // 空内容，可根据需要填充错误信息
        );

        // 异步清理请求上下文和Channel属性
        // 使用 Netty 的 EventLoop 执行清理
        ctx.channel().eventLoop().execute(() -> {
            if (requestId != null) {
                requestContextMap.remove(requestId); // 移除全局缓存
                ctx.channel().attr(REQUEST_ID_KEY).set(null);// 清理Channel属性
            }
        });

        // 设置必要的响应头
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8") // 必须包含charset
                .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())// 明确内容长度
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);// Keep-Alive复用连接


        // 发送响应并关闭连接
        ctx.writeAndFlush(response).addListener(future -> {
            if (!future.isSuccess()) {
                ReferenceCountUtil.safeRelease(content);
            }
        });
    }

}
