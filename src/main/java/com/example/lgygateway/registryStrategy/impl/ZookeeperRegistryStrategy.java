//package com.example.lgygateway.registryStrategy.impl;
//
//import com.alibaba.nacos.api.naming.pojo.Instance;
//import com.example.lgygateway.config.GatewayConfig;
//import com.example.lgygateway.registryStrategy.RegistryStrategy;
//import org.apache.zookeeper.ZooKeeper;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//import java.util.Map;
//
//@Component
//public class ZookeeperRegistryStrategy implements RegistryStrategy {
//    @Autowired
//    private GatewayConfig gatewayConfig;
//
//    public ZookeeperRegistryStrategy(GatewayConfig gatewayConfig) {
//        this.gatewayConfig = gatewayConfig;
//    }
//
//    @Override
//    public void updateRouteRules() {
//        try {
//            ZooKeeper zk = new ZooKeeper(gatewayConfig.getRegistryAddress(), 3000, null);
//            byte[] data = zk.getData("/route-rules", false, null);
//            String content = new String(data);
//            // 拿到注册中心的相关内容后 后续进行路由的处理
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    @Override
//    public Map<String, List<Instance>> getRouteRules() {
//        return Map.of();
//    }
//}