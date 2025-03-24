package com.example.lgygateway.registryStrategy.impl;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NacosConfig;
import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.route.model.Filters;
import com.example.lgygateway.route.model.Route;
import com.example.lgygateway.route.model.RouteValue;
import com.example.lgygateway.utils.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component("nacos")
@Lazy
public class NacosRegistry implements Registry, DisposableBean {
    @Autowired
    private NacosConfig nacosConfig;
    // 已订阅的服务名集合（防止重复订阅）
    private final Set<String> subscribedServices = ConcurrentHashMap.newKeySet();
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
    private final static ReentrantLock lock = new ReentrantLock();
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

    private final static String LB_PREFIX = "lb://";
    private final static ExecutorService customExecutor = Executors.newFixedThreadPool(20);
    private void parseAndUpdateRouteRules(String content) {
        try{
            //当配置文件经常修改 通过lock保证线程安全
            lock.lock();
            routeValues.clear();
            try {
                // 得到相关的实例ip
                ConcurrentHashMap<String, List<Instance>> rules = new ConcurrentHashMap<>();
                // 使用SnakeYAML解析原始YAML内容
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                // 直接解析整个 YAML 结构到 Map，提取 routes 列表
                Map<String, Object> root = yamlMapper.readValue(content, new TypeReference<>() {
                });
                List<Map<String, Object>> routesList = (List<Map<String, Object>>) root.get("routes");
                // 将 List<Map> 转换为 List<Route>
                List<Route> routes = yamlMapper.convertValue(routesList, new TypeReference<>() {
                });
                // 启用多线程优化性能
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                routes.forEach(route -> {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        // 获取服务名
                        // lb:xxxxService -> xxxxService
                        String path = route.getPredicates().getPath();
                        String uri = route.getUri();
                        String serviceName = "";
                        if (uri != null && uri.startsWith(LB_PREFIX)) {
                            serviceName = uri.substring(LB_PREFIX.length());
                        }
                        try {
                            // 获取服务实例
                            List<Instance> allInstances = namingService.getAllInstances(serviceName);
                            if (!allInstances.isEmpty()) {
                                rules.put(path, allInstances);
                            }

                            RouteValue routeValue = new RouteValue();

                            // 匹配过滤器
                            List<String> filterNames = route.getFilters().stream()
                                    .map(Filters::getName)
                                    .collect(Collectors.toList());
                            List<Filter> filters = getMatchedFilters(filterNames);

                            // 添加路由属性
                            routeValue.setFilters(filters);
                            routeValue.setMethod(route.getPredicates().getMethod());
                            routeValues.put(path, routeValue);

                            //订阅相关服务
                            if (!subscribedServices.contains(serviceName)) {
                                namingService.subscribe(serviceName, new InstanceChangeListener(serviceName, path));
                                subscribedServices.add(serviceName);
                            }
                        } catch (NacosException e) {
                            throw new RuntimeException("无法获取服务实例: " + serviceName, e);
                        }
                    },customExecutor);
                    futures.add(future);
                });
                // 等待所有任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                // 清空旧路由规则并更新
                routeRules.clear();
                routeRules.putAll(rules);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }finally {
            lock.unlock();
        }
    }
    // 预先加载所有 Filter 实现
    private static final List<Filter> ALL_FILTERS = loadAllFilters();
    private static List<Filter> loadAllFilters() {
        ServiceLoader<Filter> loader = ServiceLoader.load(Filter.class);
        List<Filter> filters = new ArrayList<>();
        loader.forEach(filters::add);
        return Collections.unmodifiableList(filters);
    }
    // 修改过滤器匹配逻辑
    private List<Filter> getMatchedFilters(List<String> filterNames) {
        return ALL_FILTERS.stream()
                .filter(filter -> filterNames.contains(filter.getClass().getSimpleName()))
                .collect(Collectors.toList());
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
                    Log.logger.info("服务实例更新:{} -> {} , 当前服务实例个数为。{}", serviceName, newInstances, newInstances.size());
                }
            } catch (NacosException e) {
                // 异常处理...
            }
        }
    }
    @Override
    public void destroy() {
        if (customExecutor != null && !customExecutor.isShutdown()) {
            customExecutor.shutdown();
            try {
                if (!customExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    customExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
