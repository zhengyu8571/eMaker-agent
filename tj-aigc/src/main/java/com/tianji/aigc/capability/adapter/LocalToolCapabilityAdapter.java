package com.tianji.aigc.capability.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.CapabilityKeys;
import com.tianji.aigc.capability.ToolBackedCapability;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.aigc.constants.Constant;
import com.tianji.common.utils.UserContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将Spring AI官方ToolCallback包装成项目统一能力。
 */
public class LocalToolCapabilityAdapter implements ToolBackedCapability {

    // 未声明稳定字段的旧工具仍保留通用对象契约
    private static final String GENERIC_OUTPUT_SCHEMA = "{\"type\":\"object\"}";
    // 规划目录只暴露可绑定的首个课程 ID，完整列表仍由工具结果保留
    private static final String COURSE_CANDIDATE_OUTPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"courseId\":{\"type\":\"integer\"},\"keyword\":{\"type\":\"string\"}}}";
    // 课程详情提供价格、适用人群等可验证字段
    private static final String COURSE_INFO_OUTPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"price\":{\"type\":\"number\"},\"validDuration\":{\"type\":\"integer\"},\"usePeople\":{\"type\":\"string\"},\"detail\":{\"type\":\"string\"}}}";

    private final ToolCallback toolCallback;
    private final ObjectMapper objectMapper;
    private final CapabilityDescriptor descriptor;

    public LocalToolCapabilityAdapter(ToolCallback toolCallback,
                                      SideEffectLevel sideEffect,
                                      boolean retryable,
                                      ObjectMapper objectMapper) {
        this.toolCallback = toolCallback;
        this.objectMapper = objectMapper;
        var toolDefinition = toolCallback.getToolDefinition();
        this.descriptor = new CapabilityDescriptor(
                CapabilityKeys.localTool(toolDefinition.name()),
                CapabilityKind.LOCAL_TOOL,
                toolDefinition.description(),
                toolDefinition.inputSchema(),
                outputSchema(toolDefinition.name()),
                sideEffect,
                Duration.ofSeconds(10),
                retryable,
                true);
    }

    // MCP适配器复用同一输出契约，避免字段绑定漂移
    static String outputSchema(String toolName) {
        // 根据已知工具的稳定结果选择对应 Schema
        return switch (toolName) {
            // 候选查询结果允许绑定首个 courseId
            case "searchCourseCandidate" -> COURSE_CANDIDATE_OUTPUT_SCHEMA;
            // 课程详情字段允许后续计划按结构化数据使用
            case "queryCourseById" -> COURSE_INFO_OUTPUT_SCHEMA;
            // 未声明字段的旧工具维持原行为
            default -> GENERIC_OUTPUT_SCHEMA;
        };
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return this.descriptor;
    }

    @Override
    public CapabilityResult execute(Map<String, Object> input,
                                    CapabilityCallContext context) {
        // 保存线程原有身份以免污染线程池中的下一次请求
        var previousUserId = UserContext.getUser();
        try {
            // Feign 用户透传从线程上下文读取当前用户
            UserContext.setUser(context.userId());
            var toolContext = new LinkedHashMap<String, Object>();
            toolContext.put(Constant.REQUEST_ID, context.requestId());
            toolContext.put(Constant.USER_ID, context.userId());
            toolContext.put("sessionId", context.sessionId());
            var jsonInput = this.objectMapper.writeValueAsString(
                    input == null ? Map.of() : input);
            var output = this.toolCallback.call(jsonInput, new ToolContext(toolContext));
            return CapabilityResult.of(readOutput(output));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("本地工具参数无法序列化："
                    + this.descriptor.key(), e);
        } finally {
            // 工具结束后恢复调用前的线程身份
            if (previousUserId == null) {
                // 原线程没有用户时清理本次身份
                UserContext.removeUser();
            } else {
                // 原线程有用户时恢复原身份
                UserContext.setUser(previousUserId);
            }
        }
    }

    public ToolCallback toolCallback() {
        return this.toolCallback;
    }

    private Object readOutput(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        try {
            return this.objectMapper.readValue(output, Object.class);
        } catch (JsonProcessingException ignored) {
            // 工具允许直接返回普通文本，无法解析时保留原值
            return output;
        }
    }
}
