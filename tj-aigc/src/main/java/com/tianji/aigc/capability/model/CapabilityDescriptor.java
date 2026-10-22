package com.tianji.aigc.capability.model;

import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.enums.SideEffectLevel;

import java.time.Duration;

/**
 * 描述一项可注册能力的稳定契约。
 */
public record CapabilityDescriptor(

        // 跨能力类型唯一的调用键
        String key,

        // Agent、RAG、本地工具或MCP工具
        CapabilityKind kind,

        // 提供给调用方和后续Planner的简短说明
        String description,

        // 能力输入的JSON Schema
        String inputSchema,

        // 能力输出的JSON Schema
        String outputSchema,

        // 能力对外部状态的影响程度
        SideEffectLevel sideEffect,

        // 单次能力调用允许的最长时间
        Duration timeout,

        // 失败后是否允许安全重试
        boolean retryable,

        // 后续Planner是否可以看到该能力
        boolean plannerVisible
) {

    public CapabilityDescriptor {
        key = requireText(key, "Capability key不能为空");
        if (kind == null) {
            throw new IllegalArgumentException("Capability类型不能为空");
        }
        description = requireText(description, "Capability说明不能为空");
        inputSchema = requireText(inputSchema, "Capability输入Schema不能为空");
        outputSchema = requireText(outputSchema, "Capability输出Schema不能为空");
        if (sideEffect == null) {
            throw new IllegalArgumentException("Capability副作用等级不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Capability超时时间必须大于0");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
