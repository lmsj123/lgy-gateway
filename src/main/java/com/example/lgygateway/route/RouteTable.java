package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.filters.init.FiltersInit;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import com.example.lgygateway.loadStrategy.LoadServer;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import jakarta.annotation.PostConstruct;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpMethod.DELETE;

@Component
public class RouteTable {
    @Autowired
    private FiltersInit filtersInit;
    @Autowired
    private RegistryFactory registryFactory;
    @Autowired
    private LoadServer loadServer;
    private ConcurrentHashMap<String, List<Instance>> routeRules;
    @PostConstruct
    public void init() {
        routeRules = registryFactory.getRegistry().getRouteRules();
    }
    public HttpUriRequest matchRoute(String url,FullHttpRequest request) {
        //由于ConcurrentHashMap并不能很好的支持原子性操作 后续会进行优化
        //也会对后续匹配进行优化
        for (ConcurrentHashMap.Entry<String, List<Instance>> entry : routeRules.entrySet()) {
            //当查询到请求中符合网关转发规则
            if (url.contains(entry.getKey()) && successFiltering(request)) {
                //获取到服务实例
                List<Instance> instances = entry.getValue();
                //根据定义的负载均衡策略选择一个服务作为转发ip
                Instance selectedInstance = loadServer.getLoadBalancerStrategy().selectInstance(instances);
                //示例： http://localhost/xxxx/api -> http://instance/api
                //获取路由规则 一般定义为 /xxxx/ -> xxxxServer 避免存在 /xxx 和 /xxxy产生冲突
                String backendPath = url.replace(entry.getKey(), "");
                String targetUrl = "http://" + selectedInstance.getIp() + ":" + selectedInstance.getPort() + backendPath;
                //获取到对应的request准备发送
                return createHttpRequest(request, targetUrl);
            }
        }
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
    private HttpUriRequest createHttpRequest(FullHttpRequest originalRequest, String targetUrl){
        //需要注意的是 以下都是基于符合http语义而编写的 当出现了get请求中也存在请求体时就会出现bug
        //所以要符合http语义
        HttpUriRequest httpRequest;
        //获取请求方法
        HttpMethod method = originalRequest.method();
        if (method.equals(GET)) {
            httpRequest = new HttpGet(targetUrl);
        } else if (method.equals(POST)) {
            HttpPost postRequest = new HttpPost(targetUrl);
            //将原始请求的请求体内的内容复制到新请求上
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

}
