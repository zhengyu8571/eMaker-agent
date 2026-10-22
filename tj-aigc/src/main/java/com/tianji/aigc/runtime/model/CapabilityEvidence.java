package com.tianji.aigc.runtime.model;

import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.dto.SourceRef;

import java.util.List;
import java.util.Map;

/**
 * 保存一次能力调用产生的结构化证据。
 */
public record CapabilityEvidence(

        // 标识本次具体能力调用
        String invocationId,

        // 标识本次调用所属的执行步骤
        String stepId,

        // 保存被调用能力的稳定键
        String capabilityKey,

        // 保存被调用能力的类型
        CapabilityKind kind,

        // 保存已经脱敏的调用参数
        Map<String, Object> sanitizedInput,

        // 保存经过限制的能力输出快照
        Object outputSnapshot,

        // 保存能力返回的可信引用来源
        List<SourceRef> sources,

        // 保存耗时和降级等附加信息
        Map<String, Object> metadata,

        // 保存失败类型，成功时为null
        CapabilityFailureCode failureCode
) {

    public CapabilityEvidence {
        // 清理并校验调用标识
        invocationId = requireText(invocationId, "能力证据的invocationId不能为空");
        // 清理并校验步骤标识
        stepId = requireText(stepId, "能力证据的stepId不能为空");
        // 清理并校验能力键
        capabilityKey = requireText(capabilityKey, "能力证据的capabilityKey不能为空");
        // 校验能力类型
        if (kind == null) {
            // 拒绝缺少能力类型的证据
            throw new IllegalArgumentException("能力证据的kind不能为空");
        }
        // 固定脱敏参数的只读快照
        sanitizedInput = sanitizedInput == null ? Map.of() : Map.copyOf(sanitizedInput);
        // 固定引用来源的只读快照
        sources = sources == null ? List.of() : List.copyOf(sources);
        // 固定附加信息的只读快照
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean successful() {
        // 没有失败类型代表本次能力调用成功
        return this.failureCode == null;
    }

    private static String requireText(String value, String message) {
        // 拒绝空白文本
        if (value == null || value.isBlank()) {
            // 抛出对应的字段校验异常
            throw new IllegalArgumentException(message);
        }
        // 返回去除首尾空白后的文本
        return value.strip();
    }
}
