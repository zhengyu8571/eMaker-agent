package com.tianji.aigc.runtime.model;

import com.tianji.aigc.capability.model.CapabilityResult;

/**
 * 统一保存一次能力调用的执行结果与证据。
 */
public record CapabilityExecutionResult(

        // 标识本次具体能力调用
        String invocationId,

        // 保存被调用能力的稳定键
        String capabilityKey,

        // 保存成功时的能力结果
        CapabilityResult result,

        // 保存能力执行后的证据快照
        EvidenceSnapshot evidence,

        // 保存失败类型，成功时为null
        CapabilityFailureCode failureCode,

        // 保存适合日志和调用方读取的失败原因
        String failureMessage
) {

    public CapabilityExecutionResult {
        // 清理并校验调用标识
        invocationId = requireText(invocationId, "能力执行结果的invocationId不能为空");
        // 清理并校验能力键
        capabilityKey = requireText(capabilityKey, "能力执行结果的capabilityKey不能为空");
        // 缺少证据时使用空快照
        evidence = evidence == null ? EvidenceSnapshot.EMPTY : evidence;
        // 清理失败原因的首尾空白
        failureMessage = failureMessage == null ? null : failureMessage.strip();
        // 校验成功结果必须包含能力输出
        if (failureCode == null && result == null) {
            // 拒绝没有业务结果的成功状态
            throw new IllegalArgumentException("成功的能力执行结果不能为空");
        }
        // 校验失败结果必须包含失败原因
        if (failureCode != null && (failureMessage == null || failureMessage.isBlank())) {
            // 拒绝缺少原因的失败状态
            throw new IllegalArgumentException("失败的能力执行原因不能为空");
        }
    }

    public static CapabilityExecutionResult success(String invocationId,
                                                    String capabilityKey,
                                                    CapabilityResult result,
                                                    EvidenceSnapshot evidence) {
        // 创建成功的能力执行结果
        return new CapabilityExecutionResult(invocationId, capabilityKey, result, evidence, null, null);
    }

    public static CapabilityExecutionResult failure(String invocationId,
                                                    String capabilityKey,
                                                    EvidenceSnapshot evidence,
                                                    CapabilityFailureCode failureCode,
                                                    String failureMessage) {
        // 创建失败的能力执行结果
        return new CapabilityExecutionResult(invocationId, capabilityKey, null, evidence, failureCode, failureMessage);
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
