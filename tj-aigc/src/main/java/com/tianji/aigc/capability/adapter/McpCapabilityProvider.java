package com.tianji.aigc.capability.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.Capability;
import com.tianji.aigc.capability.CapabilityProvider;
import com.tianji.aigc.config.McpClientProperties;
import com.tianji.aigc.mcp.McpClientManager;
import com.tianji.aigc.mcp.McpCourseTools;
import com.tianji.aigc.service.CourseQueryService;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 先注册配置声明的契约，首次调用时才发现并核验远端工具。 */
@Component
public class McpCapabilityProvider implements CapabilityProvider {
    // 固定启动时注册的只读MCP能力
    private final List<Capability> capabilities;

    // 注册过程不建立SSE连接，避免同进程Server尚未启动
    public McpCapabilityProvider(McpClientManager manager, McpClientProperties properties, CourseQueryService courseQueryService, ObjectMapper objectMapper) {
        // 用官方方法适配器生成与Server完全相同的输入Schema
        var definitions = Arrays.stream(
                MethodToolCallbackProvider.builder()
                .toolObjects(new McpCourseTools(courseQueryService))
                        .build().getToolCallbacks())
                .sorted(Comparator.comparing(callback -> callback.getToolDefinition().name()))
                .toList();
        // 保存待注册能力
        var registered = new ArrayList<Capability>();
        // 按服务ID稳定注册能力
        manager.configuredTools()
                .entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(server -> {
            // 每个服务只暴露配置允许的工具
            for (var callback : definitions) {
                // 读取工具契约
                var definition = callback.getToolDefinition();
                // 服务配置未授权的工具不注册
                if (server.getValue().contains(definition.name())) {
                    // 输出Schema与本地课程工具一致
                    registered.add(new McpCapabilityAdapter(server.getKey(), definition, LocalToolCapabilityAdapter.outputSchema(definition.name()), manager.invocationTimeout(), manager, objectMapper, properties.getMaxResponseChars()));
                }
            }
        });
        // 固定目录后交给已有CapabilityRegistry
        this.capabilities = List.copyOf(registered);
    }

    @Override
    public List<Capability> capabilities() {
        // MCP关闭时返回空目录
        return this.capabilities;
    }
}
