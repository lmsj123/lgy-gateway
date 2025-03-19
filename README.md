# lgy-gateway

#### 介绍
一个简单的自研网关，用于管理和控制从客户端到后端的api请求以及流量控制，以Netty为核心，实现全异步链路，
包含了负载均衡，请求转发，动态路由，限流等功能。
#### 软件架构
config -> 各种配置信息

controller -> 提供了一个修改端口的接口 后续可优化

filters -> 过滤链的信息

limit -> 限流相关信息

loadStrategy -> 负载均衡策略

netty -> 启动转发路由的服务

registryStrategy -> 注册中心的选择

route -> 路由表获取路由服务

utils -> 日志相关
#### 使用说明

1. 配置注册配置中心相关信息（此项目主要适用nacos举例）
2. 配置好netty的相关信息（监听端口，重试次数，qps等）
3. nacos中的路由规则一般为 -> /xxxx/ -> xxxxService
4. 通过配置META-INF.services可以动态指定过滤器和负载均衡策略（SPI机制）
5. 启动LgyGatewayApplication开启网关路由功能

#### 参与贡献

1.  Fork 本仓库
2.  新建分支
3.  提交代码
4.  新建 Pull Request

