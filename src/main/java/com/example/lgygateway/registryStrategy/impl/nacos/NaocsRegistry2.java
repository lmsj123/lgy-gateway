package com.example.lgygateway.registryStrategy.impl.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NacosConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.model.route.routeValue.RouteValue;
import com.example.lgygateway.utils.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
@Lazy
/*
   该类是针对于配置文件为简单的json格式
   示例：
       {
       "xxxx":"xxxxService"
       }
 */
public class NaocsRegistry2 implements Registry {
    @Autowired
    private NacosConfig nacosConfig;
    // 已订阅的服务名集合（防止重复订阅）
    private final Set<String> subscribedServices = new HashSet<>();
    //存储路由规则
    @Getter
    private final ConcurrentHashMap<String, List<Instance>> routeRules = new ConcurrentHashMap<>();
    //存储路由属性
    @Getter
    private final ConcurrentHashMap<String, RouteValue> routeValues = new ConcurrentHashMap<>();
    //用来通过服务名获取集群
    private NamingService namingService;
    //用于监听路由规则的更新
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
        configService = NacosFactory.createConfigService(nacosConfig.getIp() + ":" + nacosConfig.getPort());
        updateRouteRules();
        //后续监听路由规则是否更改
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
            e.fillInStackTrace();
        }
    }
    private void parseAndUpdateRouteRules(String content) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, String> newRouteRules = objectMapper.readValue(content, new TypeReference<>() {});
            Map<String, List<Instance>> rules = new HashMap<>();

            // 遍历所有路由规则中的服务名，注册实例变更监听
            newRouteRules.forEach((path, serviceName) -> {
                try {
                    // 获取当前服务实例并存入路由表
                    List<Instance> instances = namingService.getAllInstances(serviceName,true);
                    if (!instances.isEmpty()) {
                        rules.put(path, instances);
                    }
                    // 注册服务实例变更监听器（核心新增代码）
                    if (!subscribedServices.contains(serviceName)) {
                        namingService.subscribe(serviceName,new InstanceChangeListener(serviceName,path));
                        subscribedServices.add(serviceName);
                    }
                } catch (NacosException e) {
                    throw new RuntimeException("无法获取服务实例: " + serviceName, e);
                }
            });

            // 清空旧路由规则并更新
            routeRules.clear();
            routeRules.putAll(rules);
        } catch (Exception e) {
            e.fillInStackTrace();
        }
    }
    private class InstanceChangeListener implements EventListener {
        private final String serviceName;
        private final String path;

        public InstanceChangeListener(String serviceName, String path) {
            this.serviceName = serviceName;
            this.path = path;
        }

        @Override
        public void onEvent(Event event) {
            try {
                // 获取最新实例列表并更新路由
                // 只选择健康实例
                List<Instance> newInstances = namingService.selectInstances(serviceName, true);
                if (newInstances.isEmpty()) {
                    routeRules.remove(path); // 彻底删除路由条目
                    Log.logger.warn("路由[{}]所有实例已下线，路径已移除", path);
                } else {
                    routeRules.put(path, newInstances);
                    Log.logger.info("服务实例更新:{} -> {} , 当前服务实例个数为。{}", serviceName ,newInstances,newInstances.size() );
                }
            } catch (NacosException e) {
                // 异常处理...
            }
        }
    }
}

