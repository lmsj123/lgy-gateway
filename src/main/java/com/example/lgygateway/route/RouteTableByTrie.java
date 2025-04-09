package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.model.filter.FullContext;
import com.example.lgygateway.model.route.routeConfig.GrayStrategy;
import com.example.lgygateway.model.route.routeValue.RouteValue;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.utils.Log;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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
        PathTrie trie = pathTrie.searchPathTrie(split[0]);
        if (!trie.getInstances().isEmpty()) {
            String path = trie.getFinalPath();
            if (successFiltering(request,path,routeValues)) {
                Log.logger.info("该路径 {} 存在对应的路由 {} 正在得到实例", url, path);
                //根据定义的负载均衡策略选择一个服务作为转发ip
                RouteValue routeValue = routeValues.get(path);
                List<Instance> targetInstances = new ArrayList<>();
                if (routeValue.getGrayStrategy() != null) {
                    boolean isGray = checkGrayMatch(request, routeValue.getGrayStrategy());
                    String pathSuffix = isGray ? "-gray" : "-normal";
                    targetInstances = routeRules.get(path + pathSuffix);
                    if(isGray){
                        Log.logger.info("获取到灰度版本服务");
                    }else {
                        Log.logger.info("获取到非灰度版本服务");
                    }
                }
                Instance selectedInstance = routeValue.getLoadBalancerStrategy().selectInstance(targetInstances);
                //示例： http://localhost/xxxx/api -> http://instance/api
                //获取路由规则 一般定义为 /xxxx/ -> xxxxServer 避免存在 /xxx 和 /xxxy产生冲突
                String targetUrl = buildTargetUrl(url, path, selectedInstance);
                Log.logger.info("转发路径为 {}", targetUrl);
                return createProxyRequest(request, targetUrl);
            }
        }
        return null;
    }

    private static final String COOKIE = "Cookie";

    public boolean checkGrayMatch(FullHttpRequest request, GrayStrategy grayStrategy) {
        // 比例分流
        if (grayStrategy.getRatio() != null) {
            return ThreadLocalRandom.current().nextInt(100) < grayStrategy.getRatio();
        }
        // 参数匹配
        String type = grayStrategy.getType().toUpperCase();
        return switch (type) {
            case "HEADER" -> grayStrategy.getValue().equals(request.headers().get(grayStrategy.getKey()));
            case "COOKIE" -> isCookieMatch(request, grayStrategy);
            case "PARAM" -> isParamMatch(request, grayStrategy);
            default -> false;
        };
    }

    private boolean isCookieMatch(FullHttpRequest request, GrayStrategy grayStrategy) {
        String cookieHeader = request.headers().get(COOKIE);
        if (cookieHeader == null) {
            return false;
        }

        Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieHeader);
        Optional<Cookie> matchingCookie = cookies.stream()
                .filter(c -> grayStrategy.getKey().equals(c.name()))
                .findFirst();

        return matchingCookie.map(c -> grayStrategy.getValue().equals(c.value())).orElse(false);
    }

    private boolean isParamMatch(FullHttpRequest request, GrayStrategy grayStrategy) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> values = decoder.parameters().get(grayStrategy.getKey());
        if (values == null) {
            return false;
        }
        return values.contains(grayStrategy.getValue());
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

    private String buildTargetUrl(String originalUrl, String matchedPattern, Instance instance) {
        // 分离路径和查询参数
        String path = originalUrl.split("\\?")[0];
        String query = originalUrl.contains("?") ? originalUrl.split("\\?")[1] : "";

        // 提取动态路径部分（如 /python/cr_data_backflow/... → cr_data_backflow/...）
        String backendPath = path.replaceFirst(matchedPattern.replace("**", ""), "");
        // 确保路径以斜杠开头
        if (!backendPath.startsWith("/")) {
            backendPath = "/" + backendPath;
        }
        return "http://" + instance.getIp() + ":" + instance.getPort()
                + backendPath + (query.isEmpty() ? "" : "?" + query);
    }
}

