package com.example.lgygateway.registryStrategy.impl;

import ch.qos.logback.classic.spi.EventArgUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.GatewayConfig;
import com.example.lgygateway.config.NacosConfig;
import com.example.lgygateway.registryStrategy.RegistryStrategy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Lazy
public class NacosRegistryStrategy implements RegistryStrategy {
    @Autowired
    private NacosConfig nacosConfig;
    Map<String, List<Instance>> routeRules = new HashMap<>();
    private NamingService namingService;
    @Override
    public Map<String,List<Instance>> getRouteRules() {
        return routeRules;
    }
    @PostConstruct
    public void start() throws NacosException {
        if(!nacosConfig.getIp().isEmpty() && !nacosConfig.getPort().isEmpty()) {
            namingService = NacosFactory.createNamingService(nacosConfig.getIp()+ ":" + nacosConfig.getPort());
        }
    }
    @Override
    public void updateRouteRules() {
        try {
            ConfigService configService = NacosFactory.createConfigService(nacosConfig.getIp()+ ":" + nacosConfig.getPort());
            String dataId = nacosConfig.getDataId();
            String group = nacosConfig.getGroup();
            String content = configService.getConfig(dataId, group, 5000);
            //更新相关路由
            parseAndUpdateRouteRules(content);
        } catch (NacosException e) {
            e.printStackTrace();
        }
    }

    private void parseAndUpdateRouteRules(String content) {
        // 注册中心的相关路由规则可能为 JSON format: {"/xxxx": "xxxxServer", ...}
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // 示例： “/xxxx" : "http://xxxxServer"
            Map<String,String> newRouteRules = objectMapper.readValue(content, new TypeReference<>() {
            });

            Map<String, List<Instance>> rules = newRouteRules.entrySet().stream().collect(Collectors.toMap(
                    entry -> entry.getKey(),
                    entry -> {
                        try {
                            return namingService.getAllInstances(entry.getValue());
                        } catch (NacosException e) {
                            throw new RuntimeException(e);
                        }
                    }));
            routeRules.clear();
            routeRules.putAll(rules);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
