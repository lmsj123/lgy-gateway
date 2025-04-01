package com.example.lgygateway.netty.testSplit.manager;

import com.example.lgygateway.netty.testSplit.handler.BackendHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ClientBootStrapManager {
    @Autowired
    private ApplicationContext applicationContext;
    @Getter
    private Bootstrap clientBootstrap;
    @Getter
    private NioEventLoopGroup clientGroup;
    @PostConstruct
    public void init() {
        this.clientGroup = new NioEventLoopGroup();
        this.clientBootstrap = new Bootstrap();
        // [线程组绑定]
        clientBootstrap.group(clientGroup) // 绑定NioEventLoopGroup线程组，用于处理IO事件
                // [通道类型选择]
                .channel(NioSocketChannel.class) // 使用NIO非阻塞Socket通道（对比：OioSocketChannel为阻塞式）
                // [TCP保活机制]
                .option(ChannelOption.SO_KEEPALIVE, true) // 启用TCP Keep-Alive探活包，默认间隔2小时（需系统内核支持）
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 连接超时5秒
                // [管道处理器链配置]
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 从Spring容器获取Bean实例
                        BackendHandler backendHandler = applicationContext.getBean(BackendHandler.class);
                        ch.pipeline()
                                // 1. 编解码器
                                .addLast(new HttpClientCodec())
                                // 2. 聚合器
                                .addLast(new HttpObjectAggregator(65536))
                                // 3. 超时控制（应在业务处理器前）
                                .addLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS)) // 读超时
                                .addLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)) // 新增写超时
                                // 4. 业务处理器
                                .addLast(backendHandler);
                    }
                });
    }
}
