package com.tianji.aigc.runtime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.model.CapabilityInvocation;
import com.tianji.aigc.runtime.AgentCapabilityExecutionException;
import com.tianji.aigc.runtime.AgentRuntime;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将Spring AI工具请求转交统一AgentRuntime执行。
 */
public class RuntimeToolCallback implements ToolCallback {

    // 保存模型看到的原始工具定义
    private final ToolDefinition toolDefinition;

    // 保存工具对应的统一能力键
    private final String capabilityKey;

    // 保存当前请求的显式运行上下文
    private final AgentExecutionContext executionContext;

    // 执行鉴权、预算、超时和证据记录
    private final AgentRuntime agentRuntime;

    // 负责JSON工具参数和结果转换
    private final ObjectMapper objectMapper;

    // 生成当前工具在本次运行中的步骤序号
    private final AtomicInteger sequence = new AtomicInteger();

    public RuntimeToolCallback(ToolDefinition toolDefinition, String capabilityKey, AgentExecutionContext executionContext, AgentRuntime agentRuntime, ObjectMapper objectMapper) {
        // 保存原始工具定义
        this.toolDefinition = toolDefinition;
        // 保存统一能力键
        this.capabilityKey = capabilityKey;
        // 保存本次运行上下文
        this.executionContext = executionContext;
        // 保存统一能力运行时
        this.agentRuntime = agentRuntime;
        // 保存对象转换器
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 保持模型看到的工具名称、说明和Schema不变
        return this.toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        // 无显式ToolContext时仍通过当前运行上下文执行
        return execute(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 不信任模型侧上下文，统一使用服务端运行上下文
        return execute(toolInput);
    }

    private String execute(String toolInput) {
        // 解析模型生成的JSON工具参数
        var input = readInput(toolInput);
        // 生成便于证据阅读的工具步骤标识
        var stepId = "tool:" + this.toolDefinition.name() + ":" + this.sequence.incrementAndGet();
        // 通过统一运行时执行工具能力
        var executionResult = this.agentRuntime.invoke(stepId, new CapabilityInvocation(this.capabilityKey, input), this.executionContext);
        // 失败时向Spring AI工具链抛出稳定异常
        if (!executionResult.successful()) {
            // 交给模型调用链处理工具失败
            throw new AgentCapabilityExecutionException(this.capabilityKey, executionResult.failureCode(), executionResult.failureMessage());
        }
        // 读取能力返回的业务结果
        var output = executionResult.result().output();
        // 普通文本无需再次添加JSON引号
        if (output instanceof String text) {
            // 直接返回原始文本结果
            return text;
        }
        try {
            // 将结构化结果序列化为工具响应JSON
            return this.objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            // 暴露无法交给模型读取的输出错误
            throw new IllegalStateException("工具能力结果无法序列化：" + this.capabilityKey, exception);
        }
    }

    private Map<String, Object> readInput(String toolInput) {
        // 空工具参数按空对象处理
        if (toolInput == null || toolInput.isBlank()) {
            // 返回不可修改的空参数
            return Map.of();
        }
        try {
            // 将JSON对象转换为统一能力参数Map
            return this.objectMapper.readValue(toolInput, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            // 阻止非法JSON绕过能力输入校验
            throw new IllegalArgumentException("工具参数不是合法JSON对象：" + this.toolDefinition.name(), exception);
        }
    }
}
