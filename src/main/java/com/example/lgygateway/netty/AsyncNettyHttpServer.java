package com.example.lgygateway.netty;
/*
关键方法详细解读
1. initializeClientBootstrap()
功能：配置客户端连接参数，初始化ChannelPipeline。
异步点：
HttpClientCodec和HttpObjectAggregator实现HTTP请求的编码与聚合。
BackendHandler异步处理后端响应，通过ChannelHandlerContext回写结果。
2. GatewayHandler.channelRead0()
流程：
接收客户端请求，通过路由表匹配目标服务地址。
调用forwardRequestWithRetry()发起异步转发。
异步点：
路由匹配和转发操作均在EventLoop线程中执行，不阻塞其他请求
3. forwardRequestWithRetry()
流程：
解析目标URI，建立异步连接（clientBootstrap.connect()）。
通过ChannelFuture监听连接结果，成功时异步写入请求，失败时触发重试。
异步点：
连接和写入操作均通过addListener注册回调，避免阻塞当前线程
4. BackendHandler.channelRead0()
流程：
接收后端响应，复制响应头和内容。
通过frontendCtx.writeAndFlush()异步回写给客户端。
异步点：
响应回写通过ChannelFutureListener.CLOSE在完成时关闭连接，全程非阻塞
5. handleRetry()
功能：在EventLoop线程中调度延迟重试任务。
异步点：
使用eventLoop.schedule()实现非阻塞定时任务，避免阻塞I/O线程
 */

import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.RouteTable;
import com.example.lgygateway.utils.Log;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class AsyncNettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;
    // 客户端连接池，复用长连接与线程资源，避免每次转发都新建连接。
    private final Bootstrap clientBootstrap; // 客户端连接池启动器
    private final NioEventLoopGroup clientGroup; // 客户端io线程组 io多路复用
    private EventLoopGroup bossGroup; // 服务端主线程组 接受连接
    private EventLoopGroup workerGroup; // 服务端工作线程组 处理io
    // 存储原始请求
    private static final AttributeKey<FullHttpRequest> ORIGINAL_REQUEST_KEY = AttributeKey.valueOf("originalRequest");
    // 存储剩余重试次数
    private static final AttributeKey<Integer> REMAINING_RETRIES_KEY = AttributeKey.valueOf("remainingRetries");
    @Autowired
    private RouteTable routeTable; // 动态路由表
    // 初始化连接池
    private final ChannelPoolMap<InetSocketAddress, ChannelPool> poolMap;

    static class CustomPoolHandler extends AbstractChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) {
            ch.pipeline().addLast(new HttpClientCodec());
        }
    }
    @Autowired
    public AsyncNettyHttpServer(RegistryFactory registryFactory) {
        //获取注册中心
        Registry registry = registryFactory.getRegistry();
        if (registry == null) {
            throw new IllegalStateException("Registry initialization failed");
        }
        //内部是使用了io多路复用 支持高并发连接 创建客户端线程组（默认cpu核心数*2）
        this.clientGroup = new NioEventLoopGroup();
        //建立长连接，复用线程和连接资源，避免传统同步HTTP客户端的线程阻塞问题
        this.clientBootstrap = new Bootstrap();
        poolMap = new AbstractChannelPoolMap<InetSocketAddress, ChannelPool>() {
            @Override
            protected ChannelPool newPool(InetSocketAddress key) {
                return new FixedChannelPool(clientBootstrap.remoteAddress(key),
                        new CustomPoolHandler(),
                        10); // 最大连接数
            }
        };
        //配置客户端bootstrap
        initializeClientBootstrap();
    }

    private void initializeClientBootstrap() {
        clientBootstrap.group(clientGroup) // 绑定客户端线程组
                .channel(NioSocketChannel.class) // 使用nio模型
                // 启用tcp keep-alive 当连接双方长时间未发送信息 会产生探活报文判断连接是否存在
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec()) // http请求编码/响应编码
                                .addLast(new HttpObjectAggregator(65536))// 聚合分块http消息为完整对象
                                .addLast(new BackendHandler());// 处理后端服务请求
                    }
                });
    }

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(); // 主线程组（处理连接请求）
        workerGroup = new NioEventLoopGroup(); // 工作线程组 （处理io操作）
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class) // 服务端nio通道
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpObjectAggregator(65536)) // 聚合客户端请求
                                .addLast(new HttpRequestDecoder()) // 将字节流解析成http请求对象
                                .addLast(new HttpResponseEncoder()) // 将http响应对象编码成字节流
                                .addLast(new GatewayHandler()); // 处理网关逻辑
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128) // 等待连接队列大小
                .childOption(ChannelOption.SO_KEEPALIVE, true); // 保持客户端连接

        ChannelFuture f = b.bind(nettyConfig.getPort()).sync(); // 绑定端口并启动
        f.channel().closeFuture().sync(); // 阻塞直至服务端通道关闭
    }

    private class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            //路由匹配
            FullHttpRequest httpRequest = routeTable.matchRouteAsync(request.uri(), request);

            if (httpRequest != null) {
                forwardRequestWithRetry(ctx, httpRequest, nettyConfig.getTimes());// 转发请求并允许重试 次数可配置
                return;
            }
            sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND); // 未匹配路由返回404
        }
    }

    private void forwardRequestWithRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries) {
        try {
            URI uri = new URI(request.uri());
            // 创建请求副本避免引用问题
            FullHttpRequest requestCopy = request.replace(Unpooled.copiedBuffer(request.content()))
                    .retain();
            // 深拷贝请求内容防止引用问题
            requestCopy.headers().set(request.headers());

            clientBootstrap.connect(uri.getHost(), uri.getPort()).addListener((ChannelFuture connectFuture) -> {
                if (connectFuture.isSuccess()) {
                    Channel backendChannel = connectFuture.channel();
                    // 绑定原始请求和重试次数到后端通道
                    backendChannel.attr(ORIGINAL_REQUEST_KEY).set(requestCopy);
                    backendChannel.attr(REMAINING_RETRIES_KEY).set(retries);
                    backendChannel.attr(AttributeKey.valueOf("frontendCtx")).set(ctx);// 关联前端上下文
                    // 发送请求到后端服务
                    backendChannel.writeAndFlush(requestCopy);
                } else {
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY); // 502错误
                }
            });
            //释放资源
            request.release();
        } catch (URISyntaxException e) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST); // 400错误
        }
    }

    private void handleRetry(ChannelHandlerContext ctx, FullHttpRequest request, Integer retries) {
        // 在重试时记录
        Log.logger.warn("Retrying request to {} (remaining {} retries)", request.uri(), retries);
        if (retries != null && retries > 0) {
            ctx.channel().eventLoop().schedule(() ->
                            forwardRequestWithRetry(ctx, request, retries - 1),
                    1, TimeUnit.SECONDS
            );
        } else {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
        }
    }

    private class BackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        @Override
        protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse backendResponse) {
            // 获取关联的原始请求和剩余重试次数
            FullHttpRequest originalRequest = backendCtx.channel().attr(ORIGINAL_REQUEST_KEY).get();
            Integer remainingRetries = backendCtx.channel().attr(REMAINING_RETRIES_KEY).get();
            ChannelHandlerContext frontendCtx = getFrontendContext(backendCtx); // 需要实现前端上下文关联逻辑

            // 判断是否符合重试条件
            if (shouldRetry(backendResponse, originalRequest)) {
                handleRetry(frontendCtx, originalRequest, remainingRetries);
            } else {
                forwardResponseToClient(frontendCtx, backendResponse);// 正常响应转发
            }
            backendCtx.close();// 关闭后端连接
        }

        private boolean shouldRetry(FullHttpResponse response, FullHttpRequest request) {
            // 5xx错误且非POST请求时触发重试
            return response.status().code() >= 500 &&
                    response.status().code() < 600 &&
                    request != null &&
                    !request.method().equals(HttpMethod.POST);
        }

        // 在BackendHandler中获取前端上下文
        private ChannelHandlerContext getFrontendContext(ChannelHandlerContext backendCtx) {
            return backendCtx.channel().attr(AttributeKey.<ChannelHandlerContext>valueOf("frontendCtx")).get();
        }

        private void forwardResponseToClient(ChannelHandlerContext ctx, FullHttpResponse response) {
            FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                    HTTP_1_1,
                    response.status(),
                    response.content().copy()
            ); // 复制响应内容
            // 添加Keep-Alive头 保持长连接
            clientResponse.headers()
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                    .set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes());

            ctx.writeAndFlush(clientResponse)
                    .addListener(ChannelFutureListener.CLOSE); // 发送并关闭
        }
    }

    // 修改后的shutdown方法
    private void shutdown() throws InterruptedException {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                status,
                Unpooled.EMPTY_BUFFER  // 空内容，可根据需要填充错误信息
        );

        // 设置必要的响应头
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        // 发送响应并关闭连接
        ctx.writeAndFlush(response)
                .addListener(ChannelFutureListener.CLOSE);
    }
}