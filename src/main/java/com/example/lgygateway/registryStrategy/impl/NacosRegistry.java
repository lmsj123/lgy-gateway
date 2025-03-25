package com.example.lgygateway.registryStrategy.impl;

import cn.hutool.core.thread.NamedThreadFactory;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NacosConfig;
import com.example.lgygateway.filters.SPIFactory.SPIFilterFactory;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.loadStrategy.SPIFactory.SPILoadStrategyFactory;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.route.model.ConfigModel.Filters;
import com.example.lgygateway.route.model.ConfigModel.Route;
import com.example.lgygateway.route.model.value.RouteValue;
import com.example.lgygateway.utils.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
/*
推荐改进优先级：
线程池管理（避免任务堆积）
SPI加载缓存（提升性能）
增量更新（减少全量解析开销）
 */
//TODO 下午任务 添加RouteData 重构类 调研如何增量更新
@Component("nacos")
@Lazy
public class NacosRegistry implements Registry, DisposableBean {
    static class RouteData{
        final Map<String, List<Instance>> rules;
        final Map<String, RouteValue> values;
        public RouteData(Map<String, List<Instance>> rules, Map<String, RouteValue> values) {
            this.rules = rules;
            this.values = values;
        }
    }
    // 存储路由
    // 使用原子引用+副本策略保证路由更新原子性
    private final AtomicReference<RouteData> routeDataRef =
            new AtomicReference<>(new RouteData(new HashMap<>(), new HashMap<>()));

    public Map<String, List<Instance>> getRouteRules() {
        return Collections.unmodifiableMap(routeDataRef.get().rules);
    }
    public Map<String, RouteValue> getRouteValues() {
        return Collections.unmodifiableMap(routeDataRef.get().values);
    }
    @Autowired
    private NacosConfig nacosConfig;
    // 已订阅的服务名集合（防止重复订阅）
    private final ConcurrentHashMap<String, InstanceChangeListener> subscribedServices = new ConcurrentHashMap<>();
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
        Log.logger.info("准备开始加载路由规则和属性");
        CompletableFuture.runAsync(this::updateRouteRules);
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
    // 优化线程池配置（根据CPU核数动态调整）
    // 但此处需要小心 由于真正部署环境下cpu数量会较高 这会导致多个线程的上下文频繁切换 所以还是根据实际情况判断
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService customExecutor = new ThreadPoolExecutor(
            CPU_CORES * 2,       // corePoolSize
            CPU_CORES * 4,       // maximumPoolSize
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500), // 有界队列
            new NamedThreadFactory("Route-Update",true),
            new ThreadPoolExecutor.AbortPolicy() {
                public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                    Log.logger.error("Task rejected, queue full! PoolSize:{}", e.getPoolSize());
                    super.rejectedExecution(r, e);
                }
            });

    //TODO 这里存在一个缺陷 现在的path都为/xxxx/这样的格式
    //TODO 后续可以更新为/xxxx/*表示当/xxxx后面只剩一个路径才可转发 /xxxx/**表示存在/xxxx/后即可转发
    //TODO 每个path都是独一无二隔离开的 所以不会出现多个线程执行同一个oath
    private void parseAndUpdateRouteRules(String content) {
            //当配置文件经常修改 通过lock保证线程安全
            try {
                lock.lock();
                // 得到相关的实例ip
                ConcurrentHashMap<String, List<Instance>> rules = new ConcurrentHashMap<>();
                ConcurrentHashMap<String, RouteValue> values = new ConcurrentHashMap<>();
                // 使用SnakeYAML解析原始YAML内容
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                // 直接解析整个 YAML 结构到 Map，提取 routes 列表
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(content);
                List<Map<String, Object>> routesList = (List<Map<String, Object>>) root.get("routes");
                // 将 List<Map> 转换为 List<Route>
                List<Route> routes = yamlMapper.convertValue(routesList, new TypeReference<>() {});
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
                            // 路由属性
                            RouteValue routeValue = new RouteValue();

                            // 匹配过滤器
                            List<String> filterNames = route.getFilters().stream()
                                    .map(Filters::getName)
                                    .collect(Collectors.toList());
                            FilterChain filterChain = getMatchedFilters(filterNames);

                            // 匹配负载均衡策略
                            LoadBalancerStrategy loadBalancerStrategy = getMatchedLoadBalancer(route.getLoadBalancer());

                            // 添加路由属性
                            routeValue.setFilterChain(filterChain);
                            routeValue.setMethod(route.getPredicates().getMethod());
                            routeValue.setLoadBalancerStrategy(loadBalancerStrategy);
                            //可能会存在覆盖问题
                            values.put(path, routeValue);

                            //订阅相关服务
                            //由于在路由表中可能存在不同路径指向同一个服务集群 所以需要保证原子性
                            subscribedServices.computeIfAbsent(serviceName, k -> {
                                try {
                                    InstanceChangeListener instanceChangeListener = new InstanceChangeListener(k, path);
                                    namingService.subscribe(k, instanceChangeListener);
                                    return instanceChangeListener;
                                } catch (NacosException e) {
                                    throw new RuntimeException("Subscribe failed: " + k, e);
                                }

                            });
                        } catch (NacosException e) {
                            throw new RuntimeException("无法获取服务实例: " + serviceName, e);
                        }
                    }, customExecutor);
                    futures.add(future);
                });
                // 等待所有任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                // 清空旧路由规则并更新
                //TODO
                routeDataRef.set(new RouteData(rules, values));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                lock.unlock();
            }
    }

    /*
    问题描述
       使用静态变量ALL_LOADBALANCERS和ALL_FILTERS预先加载SPI实现，导致运行时新增的SPI插件（如新负载均衡策略）无法被检测到。
    风险与影响
       需重启服务才能加载新插件，降低系统灵活性。
       不符合微服务动态扩展需求。
    优化建议
       动态加载SPI：每次调用getMatchedLoadBalancer或getMatchedFilters时通过ServiceLoader重新加载
       添加缓存机制，避免频繁加载影响性能。
     */

    // 预先加载所有 LoadServer 实现
    private static final List<SPILoadStrategyFactory> ALL_LOADBALANCERS = loadAllLoadBalancers();

    private static List<SPILoadStrategyFactory> loadAllLoadBalancers() {
        ServiceLoader<SPILoadStrategyFactory> load = ServiceLoader.load(SPILoadStrategyFactory.class);
        ArrayList<SPILoadStrategyFactory> spiLoadStrategyFactories = new ArrayList<>();
        load.forEach(spiLoadStrategyFactories::add);
        return Collections.unmodifiableList(spiLoadStrategyFactories);
    }

    private LoadBalancerStrategy getMatchedLoadBalancer(String loadBalancer) {
        List<SPILoadStrategyFactory> list = ALL_LOADBALANCERS.stream()
                .filter(loadBalancerStrategy
                        -> loadBalancerStrategy.getType().equalsIgnoreCase(loadBalancer)).toList();
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0).create();
    }

    // 预先加载所有 Filter 实现
    private static final List<SPIFilterFactory> ALL_FILTERS = loadAllFilters();

    private static List<SPIFilterFactory> loadAllFilters() {
        ServiceLoader<SPIFilterFactory> loader = ServiceLoader.load(SPIFilterFactory.class);
        List<SPIFilterFactory> spiFilterFactories = new ArrayList<>();
        loader.forEach(spiFilterFactories::add);
        return Collections.unmodifiableList(spiFilterFactories);
    }

    private FilterChain getMatchedFilters(List<String> filterNames) {
        List<SPIFilterFactory> filterFactories = ALL_FILTERS.stream()
                .filter(filter -> filterNames.contains(filter.getType()))
                .toList();
        FilterChain filterChain = new FilterChain();
        filterFactories.forEach(f -> filterChain.addFilter(f.create()));
        return filterChain;
    }

    // 服务相关的监听器
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
                    routeDataRef.getAndUpdate(current -> {
                        ConcurrentHashMap<String, List<Instance>> newRules = new ConcurrentHashMap<>(current.rules);
                        ConcurrentHashMap<String, RouteValue> newValues = new ConcurrentHashMap<>(current.values);
                        newRules.remove(path);
                        return new RouteData(newRules, newValues);
                    });
                    Log.logger.warn("路由[{}]所有实例已下线，路径已移除", path);
                } else {
                    routeDataRef.getAndUpdate(current -> {
                        ConcurrentHashMap<String, List<Instance>> newRules = new ConcurrentHashMap<>(current.rules);
                        ConcurrentHashMap<String, RouteValue> newValues = new ConcurrentHashMap<>(current.values);
                        newRules.put(path,newInstances);
                        return new RouteData(newRules, newValues);
                    });
                    Log.logger.info("服务实例更新:{} -> {} , 当前服务实例个数为。{}", serviceName, newInstances, newInstances.size());
                }
            } catch (NacosException e) {
                // 异常处理...
            }
        }
    }

    @Override
    public void destroy() {
        try {
            if (namingService != null) {
                namingService.shutDown(); // 显式关闭Nacos客户端
            }
            if (configService != null) {
                configService.shutDown(); // 关闭配置服务
            }
        } catch (NacosException e) {
            Log.logger.error("Nacos client shutdown failed", e);
        }
        subscribedServices.forEach((service, listener) -> {
            try {
                namingService.unsubscribe(service, listener);
            } catch (NacosException e) {
                throw new RuntimeException(e);
            }
        });
        // 改进线程池关闭逻辑
        if (!customExecutor.isShutdown()) {
            customExecutor.shutdown();
            try {
                if (!customExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    List<Runnable> dropped = customExecutor.shutdownNow();
                    Log.logger.warn("ThreadPool dropped {} tasks", dropped.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                customExecutor.shutdownNow();
            }
        }
    }
}
