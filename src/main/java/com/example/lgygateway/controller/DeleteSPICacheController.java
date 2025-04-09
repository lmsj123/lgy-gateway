package com.example.lgygateway.controller;

import com.example.lgygateway.registryStrategy.impl.nacos.NacosRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeleteSPICacheController {
    @DeleteMapping("/delete/spi/cache")
    public void deleteSPICache() {
       NacosRegistry.clearCache();
    }
}
