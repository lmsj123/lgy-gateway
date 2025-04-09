# lgy-gateway

#### 介绍
一个简单的自研网关，用于管理和控制从客户端到后端的api请求以及流量控制，以Netty为核心，实现全异步链路，
包含了负载均衡，请求转发，动态路由，限流等功能。
#### 软件架构
一、核心架构设计
模块划分
网络层：基于Netty实现异步非阻塞IO，使用ServerBootstrap和Bootstrap分别处理服务端和客户端连接。
限流体系：
滑动窗口计数器（全局限流）
令牌桶（服务级、用户级、游客级限流）
路由匹配：通过RouteTable动态匹配后端服务地址。
连接池管理：基于FixedChannelPool实现长连接复用。
重试机制：指数退避+随机抖动，避免重试风暴。
上下文管理：requestContextMap缓存请求元数据，支持异步链路追踪。
#### 使用说明

1. 配置注册配置中心相关信息（此项目主要适用nacos举例）
2. 配置好netty的相关信息（监听端口，重试次数，qps等）
3. nacos中的路由规则一般为 -> /xxxx/ -> xxxxService
4. 通过配置META-INF.services可以动态指定过滤器和负载均衡策略（SPI机制）
5. 启动LgyGatewayApplication开启网关路由功能
6. 后续优化可以从nacos作为配置中心入手 完成动态更新相关数据
7. 配置文件举例如下： 
[img.png](img.png)

#### 参与贡献

1.  Fork 本仓库
2.  新建分支
3.  提交代码
4.  新建 Pull Request

