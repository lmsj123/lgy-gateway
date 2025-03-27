package com.example.lgygateway.registryStrategy.impl.zookeeper;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.ZookeeperConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.model.route.routeValue.RouteValue;
import org.apache.zookeeper.ZooKeeper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component("zookeeper")
public class ZookeeperRegistry implements Registry {
    @Autowired
    private ZookeeperConfig zookeeperConfig;

    public void updateRouteRules() {
        try {
            ZooKeeper zk = new ZooKeeper(zookeeperConfig.getIp() + ":" + zookeeperConfig.getPort(),
                    3000, null);
            byte[] data = zk.getData("/route-rules", false, null);
            String content = new String(data);
            // 拿到注册中心的相关内容后 后续进行路由的处理
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public ConcurrentHashMap<String, List<Instance>> getRouteRules() {
        return null;
    }

    @Override
    public ConcurrentHashMap<String, RouteValue> getRouteValues() {
        return null;
    }
}