package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.filters.init.FiltersInit;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import com.example.lgygateway.loadStrategy.LoadServer;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.utils.Log;
import io.netty.handler.codec.http.*;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpMethod.DELETE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class RouteTable {
    @Autowired
    private FiltersInit filtersInit;
    @Autowired
    private RegistryFactory registryFactory;
    @Autowired
    private LoadServer loadServer;
    public FullHttpRequest matchRouteAsync(String url,FullHttpRequest request) throws URISyntaxException {
        Log.logger.info("正在获取路由表");
        ConcurrentHashMap<String, List<Instance>> routeRules = registryFactory.getRegistry().getRouteRules();
        //由于ConcurrentHashMap并不能很好的支持原子性操作 后续会进行优化
        //也会对后续匹配进行优化
        for (ConcurrentHashMap.Entry<String, List<Instance>> entry : routeRules.entrySet()) {
            Log.logger.info("正在判断该请求是否符合转发标准 {}",url);
            //当查询到请求中符合网关转发规则
            if (url.contains(entry.getKey()) && successFiltering(request)) {
                Log.logger.info("符合转发标准，正在获取实例");
                //获取到服务实例
                List<Instance> instances = entry.getValue();
                //根据定义的负载均衡策略选择一个服务作为转发ip
                Instance selectedInstance = loadServer.getLoadBalancerStrategy().selectInstance(instances);
                //示例： http://localhost/xxxx/api -> http://instance/api
                //获取路由规则 一般定义为 /xxxx/ -> xxxxServer 避免存在 /xxx 和 /xxxy产生冲突
                String backendPath = url.replace(entry.getKey(), "");
                String targetUrl = "http://" + selectedInstance.getIp() + ":" + selectedInstance.getPort() + "/" + backendPath;
                Log.logger.info("转发路径为 {}",targetUrl);
                //获取到对应的request准备发送
                return createProxyRequest(request, targetUrl);
            }
        }
        Log.logger.info("该请求不符合转发标准 {}",url);
        return null;
    }
    private boolean successFiltering(FullHttpRequest request) {
        //这里需要做一个判断 若过滤器修改了请求（如修改Header）则需要更新
        //获取到过滤链
        FilterChain filterChain = filtersInit.getFilterChain();
        //当过滤链中出现无法过滤时 对response添加相应内容 提示无法成功过滤
        FullContext fullContext = new FullContext();
        fullContext.setRequest(request);
        filterChain.doFilter(fullContext);
        return fullContext.getResponse() == null;
    }
    // 根据原始请求创建新的HTTP请求对象
    private FullHttpRequest createProxyRequest(FullHttpRequest original, String targetUrl) throws URISyntaxException {
        URI uri = new URI(targetUrl);
        // 创建新的请求对象
        FullHttpRequest newRequest = new DefaultFullHttpRequest(
                HTTP_1_1,
                original.method(),
                String.valueOf(uri),
                original.content().copy(),
                original.headers().copy(),
                original.trailingHeaders().copy()
        );
        // 设置必要的头信息
        newRequest.headers()
                .set(HttpHeaderNames.HOST, uri.getHost())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        Log.logger.info("创建新请求完成");
        return newRequest;
    }

}
