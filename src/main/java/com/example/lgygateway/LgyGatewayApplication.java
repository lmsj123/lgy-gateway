package com.example.lgygateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class LgyGatewayApplication {

    public static void main(String[] args) {
        System.out.println(1);
        ConfigurableApplicationContext run = SpringApplication.run(LgyGatewayApplication.class, args);
    }

}
