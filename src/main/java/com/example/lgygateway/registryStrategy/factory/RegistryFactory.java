package com.example.lgygateway.registryStrategy.factory;

import com.example.lgygateway.config.RegistryConfig;
import com.example.lgygateway.registryStrategy.Registry;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class RegistryFactory {
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private RegistryConfig registryConfig;
    @Getter
    private Registry registry;
    @PostConstruct
    public void init() {
        registry = applicationContext.getBean(registryConfig.getType(), Registry.class);
    }
}
