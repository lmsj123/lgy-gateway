package com.example.lgygateway.netty;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.filters.init.FiltersInit;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import com.example.lgygateway.loadStrategy.LoadServer;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
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
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.*;

@Component
public class NettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private LoadServer loadServer;
    @Autowired
    private FiltersInit filtersInit;
    private final Registry registry;
    private final CloseableHttpClient httpClient;
    private ConcurrentHashMap<String, List<Instance>> routeRules;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Autowired
    public NettyHttpServer(RegistryFactory registryStrategyFactory) {
        try {
            Registry registry = registryStrategyFactory.getRegistry();
            if (registry == null) {
                throw new IllegalStateException("Registry initialization failed");
            }
            this.registry = registry;
            this.httpClient = HttpClients.createDefault();
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
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
                                    String path = request.uri();
                                    routeRules = registry.getRouteRules(); // Get updated route rules
                                    for (ConcurrentHashMap.Entry<String, List<Instance>> entry : routeRules.entrySet()) {
                                        //当查询到请求中符合网关转发规则
                                        if (path.contains(entry.getKey()) && successFiltering(request)) {
                                            List<Instance> instances = entry.getValue();
                                            Instance selectedInstance = loadServer.getLoadBalancerStrategy().selectInstance(instances);
                                            String backendPath = path.replace(entry.getKey(), "");
                                            String targetUrl = "http://" + selectedInstance.getIp() + ":" + selectedInstance.getPort() + backendPath;
                                            HttpUriRequest httpRequest = createHttpRequest(request, targetUrl);
                                            forwardRequestWithRetry(ctx, httpRequest, 3);
                                            return;
                                        }
                                    }
                                    // If no matching rule, return a 404 response or handle accordingly
                                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
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

    // 根据原始请求创建新的HTTP请求对象
    private HttpUriRequest createHttpRequest(FullHttpRequest originalRequest, String targetUrl) throws IOException {
        HttpUriRequest httpRequest;
        HttpMethod method = originalRequest.method();
        if (method.equals(GET)) {
            httpRequest = new HttpGet(targetUrl);
        } else if (method.equals(POST)) {
            HttpPost postRequest = new HttpPost(targetUrl);
            postRequest.setEntity(new ByteArrayEntity(originalRequest.content().array()));
            httpRequest = postRequest;
        } else if (method.equals(PUT)) {
            HttpPut putRequest = new HttpPut(targetUrl);
            putRequest.setEntity(new ByteArrayEntity(originalRequest.content().array()));
            httpRequest = putRequest;
        } else if (method.equals(DELETE)) {
            httpRequest = new HttpDelete(targetUrl);
        } else {
            throw new IllegalArgumentException("Unsupported HTTP method: " + originalRequest.method());
        }
        // 复制原始请求的头信息和请求体到新请求中
        for (Map.Entry<String, String> header : originalRequest.headers().entries()) {
            httpRequest.setHeader(header.getKey(), header.getValue());
        }
        return httpRequest;
    }

    //可添加相关的过滤条件
    private boolean successFiltering(FullHttpRequest request) {
        //这里需要做一个判断 若过滤器修改了请求（如修改Header）则需要更新
        FilterChain filterChain = filtersInit.getFilterChain();
        FullContext fullContext = new FullContext();
        fullContext.setRequest(request);
        filterChain.doFilter(fullContext);
        return fullContext.getResponse() == null;
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
    private void forwardRequestWithRetry(ChannelHandlerContext ctx, HttpUriRequest httpRequest, int maxRetries) throws Exception {
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
