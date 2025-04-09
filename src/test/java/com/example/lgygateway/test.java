package com.example.lgygateway;

import com.example.lgygateway.model.route.routeConfig.Route;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class test {
    public static void main(String[] args) throws IOException {
        String yaml = "route:\n" +
                "  version: 1.1\n" +
                "  routes:\n" +
                "    # 系统管理路由\n" +
                "    - id: 7\n" +
                "      uri: lb://xxxxService\n" +
                "      LoadBalancer: RoundRobinLoadBalancer\n" +
                "      predicates:\n" +
                "        Path: /xxxx/*\n" +
                "        Method: ALL\n" +
                "      filters:\n" +
                "        - name: AuthFilter\n" +
                "        - name: RateLimitFilter\n" +
                "      gray:\n" +
                "        type: HEADER   # 支持 HEADER/COOKIE/PARAM\n" +
                "        key: X-Gray    # 匹配的键\n" +
                "        value: true  # 匹配的值\n" +
                "        ratio: 10     # 流量比例（可选）  \n" +
                "    - id: 2\n" +
                "      uri: lb://yyyyService\n" +
                "      LoadBalancer: RoundRobinLoadBalancer\n" +
                "      predicates:\n" +
                "        Path: /yyyy/**\n" +
                "        Method: ALL\n" +
                "      filters:\n" +
                "        - name: RateLimitFilter\n" +
                "      gray:\n" +
                "        type: HEADER   # 支持 HEADER/COOKIE/PARAM\n" +
                "        key: X-Gray    # 匹配的键\n" +
                "        value: true  # 匹配的值\n" +
                "        ratio: 10     # 流量比例（可选）   \n" +
                "    - id: 3\n" +
                "      uri: lb://pythonService\n" +
                "      LoadBalancer: RoundRobinLoadBalancer\n" +
                "      predicates:\n" +
                "        Path: /python/**\n" +
                "        Method: ALL\n" +
                "      filters:\n" +
                "        - name: AuthFilter\n" +
                "      gray:\n" +
                "        type: HEADER   # 支持 HEADER/COOKIE/PARAM\n" +
                "        key: X-Gray    # 匹配的键\n" +
                "        value: true  # 匹配的值\n" +
                "        ratio: 10     # 流量比例（可选）  \n" +
                "    - id: 4\n" +
                "      uri: lb://xxxxService\n" +
                "      LoadBalancer: RoundRobinLoadBalancer\n" +
                "      predicates:\n" +
                "        Path: /xxxx/**\n" +
                "        Method: ALL\n" +
                "      filters:\n" +
                "        - name: AuthFilter\n" +
                "        - name: RateLimitFilter\n" +
                "      gray:\n" +
                "        type: HEADER   # 支持 HEADER/COOKIE/PARAM\n" +
                "        key: X-Gray    # 匹配的键\n" +
                "        value: true  # 匹配的值\n" +
                "        ratio: 10     # 流量比例（可选）  \n" +
                "   ";
        RouteConfig parse = parse(yaml);
        System.out.println(parse);
    }
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static RouteConfig parse(String yamlContent) throws IOException {
        // 直接映射到包装类
        Map<String, Object> root = yamlMapper.readValue(yamlContent, new TypeReference<>() {});
        Object route = root.get("route");
        System.out.println("Parsed route object: " + route);  // 检查是否为 Map 或 List
        return yamlMapper.convertValue(route, new TypeReference<RouteConfig>() {});
    }
    static class RouteConfig {
        @JsonProperty("version")
        private double version;
        @JsonProperty("routes")
        private List<Route> routes;
    }
    @Data
    public class RouteConfigWrapper {
        @JsonProperty("route")
        private RouteConfig routeConfig;
    }
}
