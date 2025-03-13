package com.example.lgygateway.netty;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.loadStrategy.LoadServer;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryStrategyFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import jakarta.annotation.PostConstruct;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class NettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;

    private final Registry registryStrategy;
    private final CloseableHttpClient httpClient;
    private Map<String, List<Instance>> routeRules;
    @Autowired
    private LoadServer loadServer;

    @Autowired
    public NettyHttpServer(RegistryStrategyFactory registryStrategyFactory) {
        this.registryStrategy = registryStrategyFactory.getRegistryStrategy();
        this.httpClient = HttpClients.createDefault();
    }
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch){
                            ch.pipeline().addLast(new HttpRequestDecoder());
                            ch.pipeline().addLast(new HttpResponseEncoder());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
                                    String path = request.uri();
                                    routeRules = registryStrategy.getRouteRules(); // Get updated route rules
                                    for (Map.Entry<String, List<Instance>> entry : routeRules.entrySet()) {
                                        //当查询到请求中符合网关转发规则
                                        if (path.contains(entry.getKey())) {
                                            List<Instance> instances = entry.getValue();
                                            Instance selectedInstance = loadServer.getLoadBalancerStrategy().selectInstance(instances);
                                            String targetUrl = "http://" + selectedInstance.getIp() + ":" + selectedInstance.getPort() + path;
                                            forwardRequest(ctx, request, targetUrl);
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
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    private void forwardRequest(ChannelHandlerContext ctx, FullHttpRequest request, String targetUrl) throws IOException {
        HttpGet httpGet = new HttpGet(targetUrl);
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            byte[] body = EntityUtils.toByteArray(response.getEntity());
            DefaultFullHttpResponse fullHttpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.getStatusLine().getStatusCode()), Unpooled.wrappedBuffer(body));
            ctx.writeAndFlush(fullHttpResponse).addListener(ChannelFutureListener.CLOSE);
        }
    }
    public void restart(int newPort) throws InterruptedException {
        shutdown();
        nettyConfig.setPort(newPort);
        start();
    }
    private void shutdown() {
        // 关闭 Netty 服务
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
