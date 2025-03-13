package com.example.lgygateway.registryStrategy.impl;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NacosConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Component
@Lazy
public class NacosRegistryStrategy implements Registry {
    @Autowired
    private NacosConfig nacosConfig;

    @Override
    public ConcurrentHashMap<String, List<Instance>> getRouteRules() {
        return routeRules;
    }

    private final ConcurrentHashMap<String, List<Instance>> routeRules = new ConcurrentHashMap<>();
    private NamingService namingService;
    private ConfigService configService;

    @PostConstruct
    public void start() throws NacosException {
        if (nacosConfig.getDataId().isEmpty() || nacosConfig.getGroup().isEmpty()) {
            throw new NacosException(NacosException.NOT_FOUND, "无法找到路由配置的地址");
        }
        if (nacosConfig.getIp().isEmpty() || nacosConfig.getPort().isEmpty()) {
            throw new NacosException(NacosException.NOT_FOUND, "无法找到nacos的地址");
        }
        //功能为：通过服务名拿到对应的示例
        namingService = NacosFactory.createNamingService(nacosConfig.getIp() + ":" + nacosConfig.getPort());
        updateRouteRules();
        //后续监听路由规则是否更改
        configService = NacosFactory.createConfigService(nacosConfig.getIp() + ":" + nacosConfig.getPort());
        configService.addListener(nacosConfig.getDataId(), nacosConfig.getGroup(), new Listener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                updateRouteRules();
            }

            @Override
            public Executor getExecutor() {
                return null;
            }
        });
    }

    public void updateRouteRules() {
        try {
            String dataId = nacosConfig.getDataId();
            String group = nacosConfig.getGroup();
            if (dataId.isEmpty() || group.isEmpty()) {
                throw new NacosException(NacosException.NOT_FOUND, "无法找到路由配置的地址");
            }
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
            Map<String, String> newRouteRules = objectMapper.readValue(content, new TypeReference<>() {
            });

            Map<String, List<Instance>> rules = newRouteRules.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
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
