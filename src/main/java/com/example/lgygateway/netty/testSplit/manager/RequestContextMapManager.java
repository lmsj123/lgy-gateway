package com.example.lgygateway.netty.testSplit.manager;

import com.example.lgygateway.netty.testSplit.model.RequestContext;
import io.netty.util.AttributeKey;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Getter
@Component
public class RequestContextMapManager {
    // 定义全局的请求上下文缓存（线程安全）
    private final ConcurrentHashMap<String, RequestContext> requestContextMap = new ConcurrentHashMap<>();
    // 唯一标识符的AttributeKey
    private final AttributeKey<String> REQUEST_ID_KEY = AttributeKey.valueOf("requestId");

    public AttributeKey<String> getRequestIdKey() {
        return REQUEST_ID_KEY;
    }
    //定时任务删除缓存
    private void deleteOutTime() {
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> {
                    Iterator<Map.Entry<String, RequestContext>> it = requestContextMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, RequestContext> entry = it.next();
                        if (System.currentTimeMillis() - entry.getValue().getLastAccessTime() > 300_000) { // 5分钟
                            it.remove();
                            if (entry.getValue().getOriginalRequest().refCnt() > 0) {
                                entry.getValue().getOriginalRequest().release();
                            }
                        }
                    }
                }, 5, 5, TimeUnit.MINUTES);
    }

}
