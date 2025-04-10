package com.example.lgygateway.controller;

import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class DeleteSPICacheController {
    @Autowired
    private RegistryFactory registryFactory;
    @DeleteMapping("/delete/spi/cache")
    public Map<String, Object> deleteSPICache() {
        String mes = registryFactory.getRegistry().clearCache();
        Map<String, Object> data = new HashMap<>();
        data.put("code", 200);
        data.put("message", mes);
        data.put("timestamp", System.currentTimeMillis());
        return data;
    }
}
