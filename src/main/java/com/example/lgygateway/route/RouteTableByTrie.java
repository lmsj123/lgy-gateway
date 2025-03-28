package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.model.filter.FullContext;
import com.example.lgygateway.model.route.routeValue.RouteValue;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.PathTrie;
import com.example.lgygateway.utils.Log;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class RouteTableByTrie {

    @Autowired
    private RegistryFactory registryFactory;

    //遍历 ConcurrentHashMap 时若路由规则更新，可能读到中间状态。
    public FullHttpRequest matchRouteAsync(String url, FullHttpRequest request) throws URISyntaxException {
        Log.logger.info("正在获取路由表和相关路由属性");
        Map<String, List<Instance>> routeRules = registryFactory.getRegistry().getRouteRules();
        Map<String, RouteValue> routeValues = registryFactory.getRegistry().getRouteValues();
        // 初始化前缀树
        PathTrie pathTrie = new PathTrie();
        routeRules.forEach(pathTrie::insert);
        String[] split = url.split("\\?");
        List<Instance> instances = pathTrie.search(split[0]);
        if (!instances.isEmpty()){
                Log.logger.info("该路径 {} 存在对应的路由 正在得到实例", url);
                //根据定义的负载均衡策略选择一个服务作为转发ip
                RouteValue routeValue = routeValues.get(pattern);
                Instance selectedInstance = routeValue.getLoadBalancerStrategy().selectInstance(instances);
                //示例： http://localhost/xxxx/api -> http://instance/api
                //获取路由规则 一般定义为 /xxxx/ -> xxxxServer 避免存在 /xxx 和 /xxxy产生冲突
                String targetUrl = buildTargetUrl(url, pattern, selectedInstance);
                Log.logger.info("转发路径为 {}", targetUrl);
                return createProxyRequest(request, targetUrl);
            }
        return null;
    }

    private boolean successFiltering(FullHttpRequest request, String key, Map<String, RouteValue> routeValues) {
        RouteValue routeValue = routeValues.get(key);
        if (routeValue.getMethod().equals("ALL") || routeValue.getMethod().equalsIgnoreCase(String.valueOf(request.method()))) {
            //这里需要做一个判断 若过滤器修改了请求（如修改Header）则需要更新
            //获取到过滤链
            FilterChain filterChain = routeValue.getFilterChain();
            //当过滤链中出现无法过滤时 对response添加相应内容 提示无法成功过滤
            FullContext fullContext = new FullContext();
            fullContext.setRequest(request);
            filterChain.doFilter(fullContext, 0);
            Log.logger.info("判断是否成功过滤" + (fullContext.getResponse() == null));
            return fullContext.getResponse() == null;
        }
        return false;
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

