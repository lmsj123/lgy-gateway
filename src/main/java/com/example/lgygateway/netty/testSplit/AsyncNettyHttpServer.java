package com.example.lgygateway.netty.testSplit;

import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.netty.testSplit.handler.ErrorHandler;
import com.example.lgygateway.netty.testSplit.handler.GatewayHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

//TODO
//明天目标 测试pathTrie和限流算法是否生效
//TODO 1)后续可改为异步日志 提高相对应性能
//     2)后续可以添加熔断机制，快速失败（已完成）
//     3)后续将相关配置优化为可动态调整
//     4)后续优化成可以适配https等
@Component
public class AsyncNettyHttpServer{
    private static final Logger logger = LoggerFactory.getLogger(AsyncNettyHttpServer.class);
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private ErrorHandler errorHandler;
    @Autowired
    private ApplicationContext applicationContext;
    //默认线程数=CPU核心数×2，在32核服务器上产生64线程，可能导致上下文切换开销
    private final EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors() * 2);

    @PostConstruct
    public void start() {
        CompletableFuture.runAsync(() -> {
            try {
                //内部是使用了io多路复用 支持高并发连接 创建客户端线程组（默认cpu核心数*2）
                //建立长连接，复用线程和连接资源，避免传统同步HTTP客户端的线程阻塞问题
                //配置客户端bootstrap
                // 1. 线程组初始化
                // 默认NioEventLoopGroup线程数=CPU核心数×2，在32核服务器上产生64线程，可能导致上下文切换开销
                EventLoopGroup bossGroup = new NioEventLoopGroup(2);// 主线程组（处理TCP连接请求）
                EventLoopGroup workerGroup = new NioEventLoopGroup(16);// 工作线程组（处理IO读写）
                // 2. 服务端引导类配置
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        // 3. 通道类型选择
                        .channel(NioServerSocketChannel.class) // 使用NIO模型（对比：OioServerSocketChannel为阻塞式）
                        // 4. 管道处理器链配置
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                logger.info("正在初始化GatewayHandler（请求阶段）");
                                GatewayHandler gatewayHandler = applicationContext.getBean(GatewayHandler.class);
                                ch.pipeline()
                                        // 4.1 HTTP协议编解码器（必须第一顺位）
                                        .addLast(new HttpServerCodec()) // 组合HttpRequestDecoder+HttpResponseEncoder
                                        // 4.2 请求聚合器
                                        .addLast(new HttpObjectAggregator(10 * 1024 * 1024)) // 10MB
                                        .addLast(new ChannelInboundHandlerAdapter() {
                                            // 请求内容太大了
                                            @Override
                                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                                if (cause instanceof TooLongFrameException) {
                                                    errorHandler.sendErrorResponse(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                                                }
                                            }
                                        })
                                        // 4.3 业务处理器（绑定独立线程池）
                                        .addLast(businessGroup, gatewayHandler); // 避免阻塞IO线程
                            }
                        })
                        // 5. TCP参数配置
                        .option(ChannelOption.SO_BACKLOG, 1024) // 根据预估并发量调整 等待连接队列大小
                        .childOption(ChannelOption.SO_KEEPALIVE, true) // 启用TCP Keep-Alive探活
                        .childOption(ChannelOption.AUTO_READ, true); // 自动触发Channel读事件
                // 6. 端口绑定与启动
                logger.info("netty绑定端口为{}", nettyConfig.getPort());
                ChannelFuture f = b.bind(nettyConfig.getPort()).sync(); // 同步阻塞直至端口绑定成功
                f.addListener(future -> {
                    if (!future.isSuccess()) {
                        logger.error("端口绑定失败", future.cause());
                    }
                });
                // 添加优雅关机逻辑
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                }));
                // 7. 阻塞直至服务端关闭
                f.channel().closeFuture().sync(); // 阻塞主线程（需结合优雅关机逻辑）
            } catch (Exception e) {
                e.fillInStackTrace();
            }
        });
    }

}