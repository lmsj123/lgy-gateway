package com.example.lgygateway.loadStrategy;

import com.example.lgygateway.config.LoadConfig;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@Data
public class LoadServer {
    @Autowired
    private LoadConfig loadConfig;
    @Autowired
    private ApplicationContext applicationContext;
    private LoadBalancerStrategy loadBalancerStrategy;


    @PostConstruct
    public void init() {
        loadBalancerStrategy = applicationContext.getBean(loadConfig.getLoadStrategy(), LoadBalancerStrategy.class);
    }
}
