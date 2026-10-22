# Nacos 演示配置

这里是为展示版编写的示例模板，不是原运行环境的配置导出。

1. 在自己的演示 Nacos 中创建 shared-spring.yaml、shared-redis.yaml、shared-mq.yaml、shared-mybatis.yaml、shared-feign.yaml、shared-logs.yaml，内容参考本目录。
2. 按服务 application-local.yml 的 spring.config.import 补齐对应服务配置和外围共享配置。课程等外围服务可能额外要求缓存、调度或分布式事务配置，本目录不假设已完整提供这些基础框架部署材料。
3. 把 prompts 内七个文件作为同名 Data ID 创建，默认分组 DEFAULT_GROUP，实际分组以 AIProperties 配置为准。
4. 环境变量由启动进程或部署系统注入。不要把含真实凭据的 Nacos 导出放回仓库。

本目录提供 AI 主链依赖的共享配置起点；完整运行仍需准备各业务数据库、登录鉴权、课程数据与匹配的模型和 Embedding 维度。
