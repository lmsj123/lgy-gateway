package com.example.lgygateway.model.route.routeConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GrayStrategy {
    @JsonProperty("type")
    private String type;     // 匹配类型 支持header/cookie/param
    @JsonProperty("key")
    private String key;      // 参数名
    @JsonProperty("value")
    private String value;    // 匹配值
    @JsonProperty("ratio")
    private Integer ratio;   // 百分比
}
