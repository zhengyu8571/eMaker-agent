package com.tianji.aigc.capability;

import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.model.CapabilityAccessDeniedException;
import com.tianji.aigc.capability.model.ResolvedCapabilities;
import com.tianji.aigc.skill.model.ActivatedSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 根据当前 Skill 生成请求级能力白名单。
 */
@Component
@RequiredArgsConstructor
public class CapabilityResolver {

    // Capability 注册中心
    private final CapabilityRegistry capabilityRegistry;

    /**
     * 根据 Skill 解析其允许使用的 Capability。
     */
    public ResolvedCapabilities resolve(ActivatedSkill skill) {

        // Skill 不能为空
        if (skill == null) {
            throw new IllegalArgumentException("解析Capability时Skill不能为空");
        }

        // 保存最终允许使用的 Capability
        var resolved = new LinkedHashMap<String, Capability>();

        // 遍历 Skill 允许使用的 Capability Key
        for (var capabilityKey : skill.allowedCapabilityKeys()) {

            // 从注册中心获取 Capability
            var capability = this.capabilityRegistry.require(capabilityKey);

            // 校验 Agent 边界
            verifyAgentBoundary(capability, skill);

            // MCP能力还必须属于当前Skill允许的服务
            if (capability.descriptor().kind() == CapabilityKind.MCP_TOOL && (!(capability instanceof McpBackedCapability mcp) || !skill.allowedMcpServers().contains(mcp.serverId()))) {
                // 即使伪造能力key也不能越过服务白名单
                throw new CapabilityAccessDeniedException("Skill未授权该MCP服务：" + capabilityKey);
            }

            // 加入允许访问的 Capability
            resolved.put(capabilityKey, capability);
        }

        // 封装为解析结果
        return new ResolvedCapabilities(resolved);
    }

    /**
     * 解析当前 Skill 可暴露给模型的工具回调。
     */
    public ToolCallbackProvider resolveToolCallbacks(ActivatedSkill skill) {

        // 保存工具名和 ToolCallback
        var callbacks = new LinkedHashMap<String, ToolCallback>();

        // 遍历 Skill 允许使用的所有 Capability
        for (var capability : resolve(skill).capabilities().values()) {

            // 获取 Capability 描述信息
            var descriptor = capability.descriptor();

            // 不允许 Planner 使用，或者不是工具类型则跳过
            if (!descriptor.plannerVisible()
                    || (descriptor.kind() != CapabilityKind.LOCAL_TOOL
                    && descriptor.kind() != CapabilityKind.MCP_TOOL)) {
                continue;
            }

            // 工具 Capability 必须实现 ToolBackedCapability
            if (!(capability instanceof ToolBackedCapability toolBackedCapability)) {
                throw new IllegalStateException(
                        "工具Capability未提供ToolCallback：" + descriptor.key());
            }

            // 获取 ToolCallback
            var callback = toolBackedCapability.toolCallback();

            // 获取工具名称
            var toolName = callback.getToolDefinition().name();

            // 按工具名称加入 Map
            var existing = callbacks.putIfAbsent(toolName, callback);

            // 出现同名工具则抛异常
            if (existing != null) {
                throw new IllegalStateException(
                        "当前Skill包含重名ToolCallback：" + toolName);
            }
        }

        // 转成 ToolCallbackProvider 返回
        return ToolCallbackProvider.from(callbacks.values().stream().toList());
    }

    /**
     * 校验 Skill 是否越权调用其他 Agent。
     */
    private void verifyAgentBoundary(Capability capability, ActivatedSkill skill) {

        // 不是 Agent 类型则无需校验
        if (capability.descriptor().kind() != CapabilityKind.AGENT) {
            return;
        }

        // 生成当前 Skill 应该对应的 Agent Capability Key
        var expectedKey = CapabilityKeys.agent(skill.agentType());

        // 实际 Agent Key 不一致，说明越权访问
        if (!capability.descriptor().key().equals(expectedKey)) {
            throw new CapabilityAccessDeniedException(
                    "Skill不能调用其他业务Agent：" + capability.descriptor().key());
        }
    }
}
