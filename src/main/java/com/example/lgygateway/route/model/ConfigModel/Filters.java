package com.example.lgygateway.route.model.ConfigModel;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Filters {
    @JsonProperty("name")  // 映射列表元素的 "name" 字段
    private String name;
}
