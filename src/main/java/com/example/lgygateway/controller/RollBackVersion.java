package com.example.lgygateway.controller;

import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class RollBackVersion {
    @Autowired
    private RegistryFactory registryFactory;

    // 定义一个用于接收 JSON 数据的类
    @Getter
    public static class VersionRequest {
        private double version;
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollbackVersionConfig(@RequestBody VersionRequest version) {
        String mes;
        if (version != null) {
            Registry registry = registryFactory.getRegistry();
            mes = registry.rollbackVersionConfig(version.getVersion());
        } else {
            mes = "version is null";
        }
        Map<String, Object> data = new HashMap<>();
        data.put("code", 200);
        data.put("message", mes);
        data.put("timestamp", System.currentTimeMillis());
        return data;
    }
}
