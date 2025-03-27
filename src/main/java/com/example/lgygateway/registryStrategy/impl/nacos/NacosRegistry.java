package com.example.lgygateway.registryStrategy.impl.nacos;

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
import com.example.lgygateway.model.filter.FilterChain;
import com.example.lgygateway.loadStrategy.LoadBalancerStrategy;
import com.example.lgygateway.loadStrategy.SPIFactory.SPILoadStrategyFactory;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.model.route.routeConfig.Filters;
import com.example.lgygateway.model.route.routeConfig.Route;
import com.example.lgygateway.model.route.routeValue.RouteValue;
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
//TODO 1) SPI相关的缓存策略 现在策略是每次配置文件更新时都会通过SPI机制加载相对应的实例 后续可以通过本地缓存或者Redis进行优化判断
//     2) 对对应的路由规则添加版本号，后续可支持灰度发布和回滚
@Component("nacos")
@Lazy
public class NacosRegistry implements Registry, DisposableBean {
    //rules: path -> instances
    //values: path -> RouteValue
    record RouteData(Map<String, List<Instance>> rules, Map<String, RouteValue> values) {}

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
    // 已订阅的服务名集合（防止重复订阅）service -> listener
    private final ConcurrentHashMap<String, InstanceChangeListener> subscribedServices = new ConcurrentHashMap<>();
    // 已存在的路由 用于判断是否增量或者全量更新 id -> route
    private final ConcurrentHashMap<String, Route> existingRoutes = new ConcurrentHashMap<>();
    //用来通过服务名获取集群
    private NamingService namingService;
    //用于监听路由规则的更新
    private ConfigService configService;
    private final static ReentrantLock lock = new ReentrantLock();
    private final static String LB_PREFIX = "lb://";
    // 优化线程池配置（根据CPU核数动态调整）
    // 但此处需要小心 由于真正部署环境下cpu数量会较高 这会导致多个线程的上下文频繁切换 所以还是根据实际情况判断
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService customExecutor = new ThreadPoolExecutor(
            CPU_CORES * 2,       // corePoolSize
            CPU_CORES * 4,       // maximumPoolSize
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500), // 有界队列
            new NamedThreadFactory("Route-Update", true),
            new ThreadPoolExecutor.AbortPolicy() {
                public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                    Log.logger.error("Task rejected, queue full! PoolSize:{}", e.getPoolSize());
                    super.rejectedExecution(r, e);
                }
            });

    @PostConstruct
    public void start() throws NacosException {
        if (checkNacosConfig(nacosConfig.getDataId(), nacosConfig.getGroup())) {
            throw new NacosException(NacosException.NOT_FOUND, "无法找到路由配置的地址");
        }
        if (checkNacosAddress(nacosConfig.getIp(), nacosConfig.getPort())) {
            throw new NacosException(NacosException.NOT_FOUND, "无法找到nacos的地址");
        }
        //功能为：通过服务名拿到对应的示例
        namingService = NacosFactory.createNamingService(nacosConfig.getIp() + ":" + nacosConfig.getPort());
        configService = NacosFactory.createConfigService(nacosConfig.getIp() + ":" + nacosConfig.getPort());
        Log.logger.info("准备开始加载路由规则和属性");
        // 执行加载路由规则
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
            String content = configService.getConfig(nacosConfig.getDataId(), nacosConfig.getGroup(), 5000);
            if (content.isEmpty()) {
                throw new NacosException(404, "相关路由配置不存在");
            }
            //更新相关路由
            parseAndUpdateRouteRules(content);
        } catch (NacosException e) {
            e.fillInStackTrace();
        }
    }

    //需要注意的是 这里监听的是配置文件 在我们正常开发当中 频繁多个线程修改配置文件的场景基本不存在
    //但如果后续出现了这样的场景 需要对lock锁进行优化
    private void parseAndUpdateRouteRules(String content) {
        //当配置文件经常修改 通过lock保证线程安全
        try {
            lock.lock();
            ConcurrentHashMap<String, List<Instance>> rules = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, RouteValue> values = new ConcurrentHashMap<>();
            List<Route> routes = analysisYaml(content);
            Log.logger.info("正在获取解析的routes");
            // 构建新旧路由标识映射
            Set<String> newRouteIds = routes.stream().parallel()
                    .map(Route::getId)
                    .collect(Collectors.toSet());

            // 检测被删除的路由
            Set<String> deletedRoutes = existingRoutes.keySet().stream().parallel()
                    .filter(id -> !newRouteIds.contains(id))
                    .collect(Collectors.toSet());

            if (!deletedRoutes.isEmpty()) {
                Log.logger.info("正在获取被删除的路由");
                // 处理删除的路由
                processDeletedRoutes(deletedRoutes);
            }

            // 启用多线程优化性能
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            routes.forEach(route -> {
                if (checkRouteUpdate(route)) {
                    existingRoutes.put(route.getId(), route);
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

                            values.put(path, routeValue);

                            //订阅相关服务
                            //由于在路由表中可能存在不同路径指向同一个服务集群 所以需要保证原子性
                            subscribedServices.computeIfAbsent(serviceName, k -> {
                                try {
                                    InstanceChangeListener listener = new InstanceChangeListener(k);
                                    namingService.subscribe(k, listener);
                                    Log.logger.info("{} 服务的监听器已订阅 {} 路径", k, path);
                                    return listener;
                                } catch (NacosException e) {
                                    Log.logger.error("订阅失败: {}", k, e);
                                    throw new CompletionException(e);
                                }
                            }).addPath(path); // 向已有监听器添加新路径
                        } catch (NacosException e) {
                            throw new RuntimeException("无法获取服务实例: " + serviceName, e);
                        }
                    }, customExecutor);
                    futures.add(future);
                }
            });
            // 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            // 清空旧路由规则并更新
            routeDataRef.getAndUpdate(current -> {
                Map<String, List<Instance>> mergedRules = new HashMap<>(current.rules);
                Map<String, RouteValue> mergedValues = new HashMap<>(current.values);

                // 仅更新发生变化的部分
                mergedRules.putAll(rules);
                mergedValues.putAll(values);

                return new RouteData(mergedRules, mergedValues);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    // 处理删除的路由
    private void processDeletedRoutes(Set<String> deletedRoutes) {
        deletedRoutes.forEach(routeId -> {
            Route removedRoute = existingRoutes.remove(routeId);
            String path = removedRoute.getPredicates().getPath();
            Log.logger.info("被删除的路径为 {}", path);
            // 从路由数据中移除
            routeDataRef.getAndUpdate(current -> {
                Map<String, List<Instance>> newRules = new HashMap<>(current.rules);
                Map<String, RouteValue> newValues = new HashMap<>(current.values);
                newRules.remove(path);
                newValues.remove(path);
                return new RouteData(newRules, newValues);
            });

            // 清理订阅关系 可能存在不同路径指向同一个服务 所以要判断服务数量
            try {
                cleanupSubscription(removedRoute);
            } catch (NacosException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // 清理订阅关系
    // 由于在lock所机制的干预下无需担心线程不安全问题
    private void cleanupSubscription(Route removedRoute) throws NacosException {
        String serviceName = removedRoute.getUri().substring(LB_PREFIX.length());
        InstanceChangeListener instanceChangeListener = subscribedServices.get(serviceName);
        instanceChangeListener.boundPaths.remove(removedRoute.getPredicates().getPath());
        Log.logger.info("正在判断 {} 对应的 {} 服务是否还存在其他路径引用", removedRoute.getPredicates().getPath(), serviceName);
        if (instanceChangeListener.boundPaths.isEmpty()) {
            Log.logger.info("{} 对应的 {} 服务不存在其他路径引用了", removedRoute.getPredicates().getPath(), serviceName);
            namingService.unsubscribe(serviceName, instanceChangeListener);
            subscribedServices.remove(serviceName);
        }
    }

    // 判断是否需要更新该路由
    private boolean checkRouteUpdate(Route route) {
        if (!existingRoutes.containsKey(route.getId())) {
            return true;
        }
        Route oldRoute = existingRoutes.get(route.getId());
        return !route.equals(oldRoute);
    }

    //预先加载所有 LoadServer 实现
    private static List<SPILoadStrategyFactory> loadAllLoadBalancers() {
        ServiceLoader<SPILoadStrategyFactory> load = ServiceLoader.load(SPILoadStrategyFactory.class);
        ArrayList<SPILoadStrategyFactory> spiLoadStrategyFactories = new ArrayList<>();
        load.forEach(spiLoadStrategyFactories::add);
        return Collections.unmodifiableList(spiLoadStrategyFactories);
    }

    private LoadBalancerStrategy getMatchedLoadBalancer(String loadBalancer) {
        List<SPILoadStrategyFactory> list = loadAllLoadBalancers().stream()
                .filter(loadBalancerStrategy
                        -> loadBalancerStrategy.getType().equalsIgnoreCase(loadBalancer)).toList();
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0).create();
    }

    // 预先加载所有 Filter 实现
    private static List<SPIFilterFactory> loadAllFilters() {
        ServiceLoader<SPIFilterFactory> loader = ServiceLoader.load(SPIFilterFactory.class);
        List<SPIFilterFactory> spiFilterFactories = new ArrayList<>();
        loader.forEach(spiFilterFactories::add);
        return Collections.unmodifiableList(spiFilterFactories);
    }

    private FilterChain getMatchedFilters(List<String> filterNames) {
        List<SPIFilterFactory> filterFactories = loadAllFilters().stream()
                .filter(filter -> filterNames.contains(filter.getType()))
                .toList();
        FilterChain filterChain = new FilterChain();
        filterFactories.forEach(f -> filterChain.addFilter(f.create()));
        return filterChain;
    }

    // 服务相关的监听器
    private class InstanceChangeListener implements EventListener {
        private final String serviceName;
        private final ConcurrentHashMap<String, Boolean> boundPaths = new ConcurrentHashMap<>();

        public InstanceChangeListener(String serviceName) { // 移除path参数
            this.serviceName = serviceName;
        }

        public void addPath(String path) {
            boundPaths.put(path, true);
        }
        // 只能改与服务名相关的属性
        @Override
        public void onEvent(Event event) {
            try {
                List<Instance> newInstances = namingService.selectInstances(serviceName, true);
                Log.logger.info("更新新服务的路由");
                // 遍历所有关联路径进行更新
                boundPaths.keySet().forEach(path ->
                        routeDataRef.updateAndGet(current -> {
                            Map<String, List<Instance>> newRules = new HashMap<>(current.rules);
                            if (newInstances.isEmpty()) {
                                Log.logger.info("正在删除 {} 服务", serviceName);
                                newRules.remove(path);
                            } else {
                                Log.logger.info("正在更新 {} 的 {} 服务",path, serviceName);
                                newRules.put(path, newInstances);
                            }
                            return new RouteData(newRules, current.values);
                        })
                );
            } catch (NacosException e) {
                // 异常处理...
            }
        }

    }

    // 校验nacos路由文件是否配置
    public boolean checkNacosConfig(String dataId, String group) {
        return dataId.isEmpty() || group.isEmpty();
    }

    // 校验nacos ip文件是否配置
    public boolean checkNacosAddress(String ip, String port) {
        return ip.isEmpty() || port.isEmpty();
    }

    // 解析yaml配置文件
    public List<Route> analysisYaml(String content) {
        // 使用SnakeYAML解析原始YAML内容
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        // 直接解析整个 YAML 结构到 Map，提取 routes 列表
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        List<Map<String, Object>> routesList = (List<Map<String, Object>>) root.get("routes");
        // 将 List<Map> 转换为 List<Route>
        return yamlMapper.convertValue(routesList, new TypeReference<>() {
        });
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
                Log.logger.warn("Unsubscribe failed for service: {}", service, e);
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
