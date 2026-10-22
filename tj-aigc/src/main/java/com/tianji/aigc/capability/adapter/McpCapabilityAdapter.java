package com.tianji.aigc.capability.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.CapabilityKeys;
import com.tianji.aigc.capability.McpBackedCapability;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.capability.model.CapabilityAccessDeniedException;
import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.aigc.mcp.McpClientManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 将已知课程MCP工具接入统一Runtime，保留结构化业务输出。 */
public class McpCapabilityAdapter implements McpBackedCapability {
    // 来源服务ID用于二次鉴权
    private final String serverId;
    // 远端调用与模型注册共用的工具定义
    private final ToolDefinition toolDefinition;
    // 缓存协议客户端
    private final McpClientManager clientManager;
    // 解析JSON输入和结果
    private final ObjectMapper objectMapper;
    // 控制响应解析的体积
    private final int maxResponseChars;
    // 固定授权、预算和字段绑定所需的描述
    private final CapabilityDescriptor descriptor;

    // 将本地声明的只读契约包装为远端能力
    public McpCapabilityAdapter(String serverId, ToolDefinition toolDefinition, String outputSchema, Duration timeout, McpClientManager clientManager, ObjectMapper objectMapper, int maxResponseChars) {
        // 保存来源服务
        this.serverId = serverId;
        // 保存输入契约和工具名
        this.toolDefinition = toolDefinition;
        // 保存客户端管理器
        this.clientManager = clientManager;
        // 保存JSON转换器
        this.objectMapper = objectMapper;
        // 响应上限必须为正数
        if (maxResponseChars <= 0) {
            // 拒绝无界或无效响应配置
            throw new IllegalArgumentException("MCP响应字符上限必须大于0");
        }
        // 保存解析上限
        this.maxResponseChars = maxResponseChars;
        // 首版仅注册已知只读工具
        this.descriptor = new CapabilityDescriptor(CapabilityKeys.mcpTool(serverId, toolDefinition.name()), CapabilityKind.MCP_TOOL, toolDefinition.description(), toolDefinition.inputSchema(), outputSchema, SideEffectLevel.READ_ONLY, timeout, true, true);
    }

    @Override
    public String serverId() {
        // 返回不包含连接凭据的服务标识
        return this.serverId;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        // 返回启动时固定的能力契约
        return this.descriptor;
    }

    @Override
    public CapabilityResult execute(Map<String, Object> input, CapabilityCallContext context) {
        // 适配器仍校验本次Skill允许的服务
        if (context == null || !context.skill().allowedMcpServers().contains(this.serverId)) {
            // 未授权时不发起网络请求
            throw new CapabilityAccessDeniedException("当前Skill未授权MCP服务");
        }
        // 仅把业务结果写入output，来源信息放入metadata
        return new CapabilityResult(callRemote(input), List.of(), Map.of("mcpServerId", this.serverId, "remoteToolName", this.toolDefinition.name()));
    }

    @Override
    public ToolCallback toolCallback() {
        // 无Runtime时保持阶段四工具注册方式
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                // 模型读取固定的工具名和输入Schema
                return McpCapabilityAdapter.this.toolDefinition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    // 输入必须是结构化对象
                    var input = objectMapper.readValue(toolInput, new TypeReference<Map<String, Object>>() { });
                    // 远端结果转换为Spring AI工具响应
                    return objectMapper.writeValueAsString(callRemote(input));
                } catch (JsonProcessingException exception) {
                    // 不把原始输入写入异常
                    throw new IllegalArgumentException("MCP工具输入或结果不是合法JSON");
                }
            }
        };
    }

    // 两条调用路径都复用同一协议转换
    private Object callRemote(Map<String, Object> input) {
        // 传入固定工具名及Schema，实际调用前校验发现结果
        var result = this.clientManager.call(this.serverId, this.toolDefinition.name(), input == null ? Map.of() : input, this.toolDefinition.inputSchema(), this.objectMapper);
        // 远端业务失败不能伪装成成功Evidence
        if (Boolean.TRUE.equals(result.isError())) {
            // 保留稳定失败，不传播敏感远端文本
            throw new IllegalStateException("MCP课程工具返回业务错误");
        }
        // 首版课程工具只能返回一个JSON文本块
        if (result.content() == null || result.content().size() != 1 || !(result.content().get(0) instanceof McpSchema.TextContent text)) {
            // 拒绝无法作为下游字段绑定的响应
            throw new IllegalStateException("MCP课程工具结果必须是单个JSON文本块");
        }
        // 限制远端输出进入JSON解析和模型上下文的大小
        if (text.text() == null || text.text().length() > this.maxResponseChars) {
            // 大响应不能无界写入Evidence
            throw new IllegalStateException("MCP课程工具结果为空或超过字符上限");
        }
        try {
            // 解开MCP content外壳以便STEP_OUTPUT.courseId直接绑定
            return this.objectMapper.readValue(text.text(), Object.class);
        } catch (JsonProcessingException exception) {
            // 不把不可解析文本当作课程数据
            throw new IllegalStateException("MCP课程工具结果不是合法JSON");
        }
    }
}
