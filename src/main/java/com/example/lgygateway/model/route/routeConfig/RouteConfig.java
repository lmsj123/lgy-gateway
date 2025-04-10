package com.example.lgygateway.model.route.routeConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
@Data
public class RouteConfig {
    @JsonProperty("version")
    private double version;
    @JsonProperty("routes")
    private List<Route> routes;
}