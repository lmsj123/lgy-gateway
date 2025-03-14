package com.example.lgygateway.controller;


import com.example.lgygateway.netty.NettyHttpServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

//后续通过nacos配置相关动态更新端口的操作
@RestController
public class NettyController {

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @PostMapping("/change-port")
    public String changePort(@RequestParam int newPort) throws InterruptedException {
        nettyHttpServer.restartNetty(newPort);  // 重启 Netty 服务
        return "Netty port changed to: " + newPort;
    }
}