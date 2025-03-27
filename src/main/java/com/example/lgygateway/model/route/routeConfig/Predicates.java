package com.example.lgygateway.model.route.routeConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Predicates {
    @JsonProperty("Path")   // 映射 YAML 的 "Path"（注意大小写）
    private String path;

    @JsonProperty("Method")
    private String method;
}