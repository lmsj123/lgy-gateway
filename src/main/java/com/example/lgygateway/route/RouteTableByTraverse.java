package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.model.filter.FullContext;
import com.example.lgygateway.model.route.routeValue.RouteValue;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

// TODO：1) 当前路由规则很简单 当请求路径包含/xxxx/就符合 后续应该优化复杂一些 比如/xxxx/* 代码/xxxx/后只允许一个参数 /xxxx/** 表示有无均可（目前已简单解决）
//       2) 路由匹配算法优化 现在方法为contains且是遍历map 时间复杂度为O(n) 需要进行相关的优化（现已存在两种方案）
@Component
public class RouteTableByTraverse {

    @Autowired
    private RegistryFactory registryFactory;

    //遍历 ConcurrentHashMap 时若路由规则更新，可能读到中间状态。
    public FullHttpRequest matchRouteAsync(String url, FullHttpRequest request) throws URISyntaxException {
        Log.logger.info("正在获取路由表和相关路由属性");
        Map<String, List<Instance>> routeRules = registryFactory.getRegistry().getRouteRules();
        Map<String, RouteValue> routeValues = registryFactory.getRegistry().getRouteValues();

        // 按优先级排序路由规则
        List<String> sortedPatterns = routeRules.keySet().stream()
                .sorted(this::compareRouteSpecificity)
                .toList();

        for (String pattern : sortedPatterns) {
            // 转换为正则表达式
            String regex = convertToRegex(pattern);
            Pattern compiledPattern = Pattern.compile(regex);
            Matcher matcher = compiledPattern.matcher(url);
            if (matcher.matches()) {
                if (successFiltering(request, pattern, routeValues)) {
                    Log.logger.info("该路径 {} 存在对应的路由 {} 正在得到实例", url, pattern);
                    List<Instance> instances = routeRules.get(pattern);
                    if (instances.isEmpty()) {
                        Log.logger.info("不存在对应实例");
                        return null;
                    }
                    //根据定义的负载均衡策略选择一个服务作为转发ip
                    RouteValue routeValue = routeValues.get(pattern);
                    Instance selectedInstance = routeValue.getLoadBalancerStrategy().selectInstance(instances);
                    //示例： http://localhost/xxxx/api -> http://instance/api
                    //获取路由规则 一般定义为 /xxxx/ -> xxxxServer 避免存在 /xxx 和 /xxxy产生冲突
                    String targetUrl = buildTargetUrl(url, pattern, selectedInstance);
                    Log.logger.info("转发路径为 {}", targetUrl);
                    return createProxyRequest(request, targetUrl);
                }
            }
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

    //将 Ant 风格通配符转换为正则表达式
    private String convertToRegex(String pattern) {
        // /python/.*
        // 步骤1: 将 ** 替换为临时标记
        String tempMarker = UUID.randomUUID().toString();
        String temp = pattern.replace("**", tempMarker);
        // 步骤2: 处理其他转义和替换
        String regex = temp
                .replace(".", "\\.")
                .replace("*", "[^/]*");
        // 步骤3: 将临时标记替换为 .*
        regex = regex.replace(tempMarker, ".*");
        return "^" + regex + "(\\?.*)?$";
    }

    //通配符数量和路径长度进行综合排序
    private int compareRouteSpecificity(String a, String b) {
        // 1. 优先比较通配符数量
        int wildcardCompare = Integer.compare(countWildcards(a), countWildcards(b));
        if (wildcardCompare != 0) {
            return wildcardCompare; // 通配符少的优先级高
        }
        // 2. 通配符数量相同，则路径更长的优先级高
        return Integer.compare(b.length(), a.length());
    }

    //场景假设：
    //    假设有以下路由规则：
    //     /api/test → 通配符数 0，长度 9
    //     /api/* → 通配符数 1，长度 6
    //     /api/** → 通配符数 2，长度 7
    //     /api/*/detail → 通配符数 1，长度 12
    //    排序结果
    //     /api/test（通配符最少）
    //     /api/*/detail（通配符相同，但路径更长）
    //     /api/*（通配符相同，路径更短）
    //     /api/**（通配符最多）
    private int countWildcards(String pattern) {
        int count = 0;
        // 遍历字符串，匹配通配符
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                // 检查是否是 **（连续两个 *）
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    count += 2;  // ** 视为更高优先级（或更低权重）
                    i++;         // 跳过下一个 *
                } else {
                    count += 1;  // * 视为单层通配符
                }
            }
        }
        return count;
    }

    private String buildTargetUrl(String originalUrl, String matchedPattern, Instance instance) {
        // 分离路径和查询参数
        String path = originalUrl.split("\\?")[0];
        String query = originalUrl.contains("?") ? originalUrl.split("\\?")[1] : "";

        // 生成正则表达式并验证完整匹配
        String regexPattern = convertToRegex(matchedPattern);
        Pattern pattern = Pattern.compile(regexPattern);
        Matcher matcher = pattern.matcher(path);

        if (matcher.matches()) {  // 使用 matches() 确保完整匹配
            // 提取动态路径部分（如 /python/cr_data_backflow/... → cr_data_backflow/...）
            String backendPath = path.replaceFirst(matchedPattern.replace("**", ""), "");
            // 确保路径以斜杠开头
            if (!backendPath.startsWith("/")) {
                backendPath = "/" + backendPath;
            }
            return "http://" + instance.getIp() + ":" + instance.getPort()
                    + backendPath + (query.isEmpty() ? "" : "?" + query);
        }
        throw new IllegalArgumentException("URL does not match the pattern: " + matchedPattern);
    }
}
