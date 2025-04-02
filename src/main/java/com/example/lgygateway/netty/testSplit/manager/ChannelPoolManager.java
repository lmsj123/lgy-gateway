package com.example.lgygateway.netty.testSplit.manager;

import com.example.lgygateway.netty.testSplit.handler.BackendHandler;
import io.netty.channel.Channel;
import io.netty.channel.pool.*;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Component
public class ChannelPoolManager {
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private ClientBootStrapManager clientBootStrapManager;
    Logger logger = LoggerFactory.getLogger(ChannelPoolManager.class);
    // 初始化连接池
    @Getter
    private ChannelPoolMap<InetSocketAddress, FixedChannelPool> poolMap;
    private static final int MAX_CONNECTIONS = 50;      // 每个后端地址最大连接数
    private static final int ACQUIRE_TIMEOUT_MS = 5000; // 获取连接超时时间
    @PostConstruct
    public void init() {
        this.poolMap = new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(InetSocketAddress key) {
                logger.info("正在初始化 {}:{} 的连接池", key.getAddress(), key.getPort());
                FixedChannelPool pool = new FixedChannelPool(
                        clientBootStrapManager.getClientBootstrap().remoteAddress(key),
                        new CustomChannelPoolHandler(),
                        ChannelHealthChecker.ACTIVE, //使用默认健康检查
                        FixedChannelPool.AcquireTimeoutAction.FAIL,//获取超时后抛出异常
                        5000,
                        MAX_CONNECTIONS,
                        ACQUIRE_TIMEOUT_MS
                );
                logger.info("正在预热连接");
                // 异步预热10%连接
                new Thread(() -> {
                    int warmupConnections = (int) (MAX_CONNECTIONS * 0.1);
                    for (int i = 0; i < warmupConnections; i++) {
                        pool.acquire().addListener(future -> {
                            if (future.isSuccess()) {
                                Channel ch = (Channel) future.getNow();
                                pool.release(ch); // 立即释放连接至池中
                                //释放操作并非关闭连接，而是将其标记为空闲状态，供后续请求复用。
                                logger.info("预热连接 {} 已释放至连接池 为空闲状态", ch.id());
                            } else {
                                logger.error("预热失败: {}", future.cause().getMessage());
                            }
                        });
                    }
                }).start();
                return pool;
            }
        };
    }
    // 自定义ChannelPoolHandler
    class CustomChannelPoolHandler extends AbstractChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) {
            BackendHandler backendHandler = applicationContext.getBean(BackendHandler.class);
            // 初始化Channel的Pipeline（与客户端Bootstrap配置一致）
            ch.pipeline()
                    .addLast(new HttpClientCodec()) // 客户端应使用HttpClientCodec
                    .addLast(new HttpObjectAggregator(65536))
                    .addLast(backendHandler);
        }
    }
}
