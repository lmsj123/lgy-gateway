package com.example.lgygateway.route.model;

import com.example.lgygateway.filters.Filter;
import lombok.Data;

import java.util.List;

@Data
public class RouteValue {
    private String method;
    private List<Filter> filters;
}
