package com.example.lgygateway.netty;
/*
1. 服务启动阶段（start()方法）
初始化Netty线程组：使用NioEventLoopGroup创建Boss（负责连接）和Worker（负责I/O）线程组
配置ServerBootstrap：
指定通道类型为NioServerSocketChannel（NIO模型）
添加HTTP编解码器和聚合器（HttpObjectAggregator），支持完整HTTP请求处理
自定义SimpleChannelInboundHandler处理业务逻辑。
2. 请求处理阶段（channelRead0方法）
路由匹配：通过RouteTable.matchRoute()根据URI匹配目标地址（例如/api/映射到具体服务实例）
请求转发：
根据原始请求方法（GET/POST/PUT/DELETE）创建新的HttpUriRequest对象，并复制请求头和Body
调用forwardRequestWithRetry进行转发，支持最多3次重试。
3. HTTP客户端转发（forwardRequest方法）
同步HTTP调用：使用CloseableHttpClient发送请求，接收响应后封装为Netty的FullHttpResponse返回客户端
连接池优化：通过PoolingHttpClientConnectionManager复用HTTP连接，设置最大连接数为200
4. 重试机制（forwardRequestWithRetry方法）
异步调度重试：若转发失败（如服务器返回5xx错误），通过eventLoop.schedule()在Netty事件循环线程中延迟1秒重试，避免阻塞I/O线程
 */
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.RouteTable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import jakarta.annotation.PostConstruct;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class NettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;

    @Autowired
    private RouteTable routeTable;
    private final CloseableHttpClient httpClient;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Autowired
    public NettyHttpServer(RegistryFactory registryFactory) {
        try {
            // 使用连接池提高线程复用率
            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            cm.setMaxTotal(200); // 最大连接数
            this.httpClient = HttpClients.custom().setConnectionManager(cm).build();
            //获取注册中心
            Registry registry = registryFactory.getRegistry();
            if (registry == null) {
                throw new IllegalStateException("Registry initialization failed");
            }

        } catch (Exception e) {
            //抛出异常阻止bean初始化 否则容易出现bug
            throw new IllegalStateException("Failed to initialize NettyHttpServer", e);
        }
    }

    @PostConstruct
    public void start() throws InterruptedException, IOException {
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 指定最大聚合内容长度
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new HttpRequestDecoder());
                            ch.pipeline().addLast(new HttpResponseEncoder());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request){
                                    //获取监听地址
                                    String path = request.uri();
                                    //拿到转发地址
                                    HttpUriRequest httpRequest = routeTable.matchRoute(path, request);
                                    if (httpRequest != null) {
                                        //获取到对应的request准备发送
                                        forwardRequestWithRetry(ctx, httpRequest, 3);
                                        request.release();  // 新增释放操作
                                        return;
                                    }
                                    // 如果并没有匹配到
                                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                    request.release();
                                }
                            });
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(nettyConfig.getPort()).sync();
            f.channel().closeFuture().sync();
        } finally {
            httpClient.close();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();

        }
    }

    //向相关服务发送请求无重试
    private void forwardRequest(ChannelHandlerContext ctx, HttpUriRequest httpRequest) throws IOException {
        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            //此为转发至的服务器发生的一些问题（比如网络问题，限流问题）需要重试
            if (statusCode >= 500) {
                throw new IOException("Server returned HTTP status code " + statusCode);
            }
            //byte[] body = EntityUtils.toByteArray(response.getEntity()); 未释放底层资源
            // 确保实体被消费
            HttpEntity entity = response.getEntity();
            byte[] body = EntityUtils.toByteArray(entity);
            EntityUtils.consumeQuietly(entity);
            DefaultFullHttpResponse fullHttpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode), Unpooled.wrappedBuffer(body));
            //将响应头的相关信息添加到返回response上
            for (Header header : response.getAllHeaders()) {
                fullHttpResponse.headers().set(header.getName(), header.getValue());
            }
            // 响应头缺少Connection: keep-alive
            fullHttpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(fullHttpResponse).addListener(ChannelFutureListener.CLOSE);
        }
    }

    //向相关服务发送请求有规定重试次数
    private void forwardRequestWithRetry(ChannelHandlerContext ctx, HttpUriRequest httpRequest, int maxRetries){
        //计算重试次数
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                forwardRequest(ctx, httpRequest);
                return; // 如果成功，直接返回
            } catch (IOException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    // 如果达到最大重试次数，返回错误响应
                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    //Thread.sleep(1000); 如果使用这样会阻塞当前线程（即EventLoop线程）
                    // 等待一段时间后再重试（例如，1秒）
                    ctx.channel().eventLoop().schedule(() ->
                            {
                                try {
                                    forwardRequestWithRetry(ctx, httpRequest, maxRetries - 1);
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            },
                            1, TimeUnit.SECONDS
                    );
                }
            }
        }
    }

    //重启netty操作
    public void restartNetty(int newPort) throws InterruptedException, IOException {
        shutdown();
        nettyConfig.setPort(newPort);
        start();
    }

    //关闭netty操作
    private void shutdown() throws IOException {
        // 关闭 Netty 服务
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }


}
