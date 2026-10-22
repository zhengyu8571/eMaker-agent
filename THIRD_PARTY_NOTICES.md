# 框架与第三方来源

- **天机学堂基础框架**：本展示版本保留原有 tj-* Maven 模块、com.tianji 包名及课程、搜索、交易、学习、鉴权等基础业务结构，同时沿用基础 AI 对话、课程工具、会话实体等已有实现。它们是 Agent 扩展的基础，不能把全部业务及 AI 基础代码归为原创实现。
- **Agent 展示重点**：在上述基础上扩展 tj-aigc 的路由、Skill/Capability、统一 Runtime、混合 RAG、MCP 适配、规划执行、证据和增强记忆机制，具体责任边界见项目 README 与源码。
- **第三方组件**：使用 Spring Boot、Spring AI、Spring Cloud Alibaba、MCP Java SDK、MyBatis-Plus、Apache Tika、Redis/Elasticsearch 客户端等。组件清单与版本以各模块 pom.xml 为准，其名称和版权归各自权利人。
- 本展示目录未复制原项目的 Git 历史、私有语料库、第三方知识语料镜像或原运行环境导出；接口请求示例与示例提示词为展示重新编写。
