package com.tianji.aigc.runtime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.ToolBackedCapability;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.runtime.AgentRuntime;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 从请求级能力白名单创建受运行时保护的工具回调。
 */
@Component
public class RuntimeToolCallbackFactory {

    // 执行工具能力并落实运行边界
    private final AgentRuntime agentRuntime;

    // 负责工具参数和结果的JSON转换
    private final ObjectMapper objectMapper;

    public RuntimeToolCallbackFactory(AgentRuntime agentRuntime, ObjectMapper objectMapper) {
        // 保存统一能力运行时
        this.agentRuntime = agentRuntime;
        // 保存对象转换器
        this.objectMapper = objectMapper;
    }

    public ToolCallbackProvider create(AgentExecutionContext context) {
        // 校验创建工具回调所需的运行上下文
        if (context == null) {
            // 拒绝缺少权限范围的工具注册
            throw new IllegalArgumentException("创建Runtime ToolCallback时执行上下文不能为空");
        }
        // 按Skill声明顺序保存工具回调
        var callbacks = new LinkedHashMap<String, ToolCallback>();
        // 遍历请求级能力白名单
        for (var capability : context.capabilities().capabilities().values()) {
            // 读取能力稳定描述
            var descriptor = capability.descriptor();
            // 判断能力是否可以暴露给模型规划
            if (!descriptor.plannerVisible()) {
                // 跳过仅供服务端使用的能力
                continue;
            }
            // 判断能力是否属于工具类型
            if (descriptor.kind() != CapabilityKind.LOCAL_TOOL && descriptor.kind() != CapabilityKind.MCP_TOOL) {
                // 跳过Agent和RAG能力
                continue;
            }
            // 校验工具能力是否提供原始ToolCallback
            if (!(capability instanceof ToolBackedCapability toolBackedCapability)) {
                // 阻止缺少执行适配器的工具暴露给模型
                throw new IllegalStateException("工具Capability未提供ToolCallback：" + descriptor.key());
            }
            // 读取模型需要的原始工具定义
            var toolDefinition = toolBackedCapability.toolCallback().getToolDefinition();
            // 创建经过统一运行时保护的工具回调
            var callback = new RuntimeToolCallback(toolDefinition, descriptor.key(), context, this.agentRuntime, this.objectMapper);
            // 按Spring AI工具名注册回调
            var existing = callbacks.putIfAbsent(toolDefinition.name(), callback);
            // 同一请求不允许出现重名工具
            if (existing != null) {
                // 阻止模型无法区分两个同名工具
                throw new IllegalStateException("当前Skill包含重名ToolCallback：" + toolDefinition.name());
            }
        }
        // 返回Spring AI可直接注册的工具集合
        return ToolCallbackProvider.from(callbacks.values().stream().toList());
    }
}
