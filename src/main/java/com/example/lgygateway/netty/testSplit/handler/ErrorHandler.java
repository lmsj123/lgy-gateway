package com.example.lgygateway.netty.testSplit.handler;

import cn.hutool.json.JSONUtil;
import com.example.lgygateway.netty.testSplit.manager.RequestContextMapManager;
import com.example.lgygateway.netty.testSplit.model.RequestContext;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class ErrorHandler {
    @Autowired
    private RequestContextMapManager requestContextMapManager;
    private AttributeKey<String> REQUEST_ID_KEY;
    private ConcurrentHashMap<String, RequestContext> requestContextMap;

    @PostConstruct
    public void init() {
        requestContextMap = requestContextMapManager.getRequestContextMap();
        REQUEST_ID_KEY = requestContextMapManager.getREQUEST_ID_KEY();
    }

    Logger logger = LoggerFactory.getLogger(ErrorHandler.class);

    // 发送失败响应给客户端
    public void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
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
