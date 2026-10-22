package com.tianji.aigc.capability.model;

import java.util.Map;

/**
 * 保存一次能力调用的目标和结构化输入。
 */
public record CapabilityInvocation(

        // 要调用的统一能力键
        String capabilityKey,

        // 通过输入Schema校验的能力参数
        Map<String, Object> input
) {

    public CapabilityInvocation {
        if (capabilityKey == null || capabilityKey.isBlank()) {
            throw new IllegalArgumentException("调用的Capability key不能为空");
        }
        capabilityKey = capabilityKey.strip();
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
