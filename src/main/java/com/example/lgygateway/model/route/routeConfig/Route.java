package com.example.lgygateway.model.route.routeConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode
public class Route {
    private String id;

    @JsonProperty("uri")  // 映射 YAML 的 "uri" 到 Java 的 "uri" 字段
    private String uri;

    @JsonProperty("predicates")  // 嵌套对象
    private Predicates predicates;

    @JsonProperty("filters")    // 嵌套列表
    private List<Filters> filters = new ArrayList<>();

    @JsonProperty("LoadBalancer")
    private String loadBalancer;

    @JsonProperty("gray")
    private GrayStrategy grayStrategy; // 新增灰度策略字段
}
