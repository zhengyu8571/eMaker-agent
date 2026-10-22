package com.tianji.aigc.runtime;

import com.tianji.aigc.runtime.model.CapabilityFailureCode;

/**
 * 将统一运行时失败传递给Spring AI工具调用链。
 */
public class AgentCapabilityExecutionException extends RuntimeException {

    // 保存失败能力的稳定键
    private final String capabilityKey;

    // 保存运行时映射后的稳定失败类型
    private final CapabilityFailureCode failureCode;

    public AgentCapabilityExecutionException(String capabilityKey, CapabilityFailureCode failureCode, String message) {
        // 保存适合工具调用链读取的失败原因
        super(message);
        // 保存失败能力键
        this.capabilityKey = capabilityKey;
        // 保存稳定失败类型
        this.failureCode = failureCode;
    }

    public String getCapabilityKey() {
        // 返回失败能力键
        return this.capabilityKey;
    }

    public CapabilityFailureCode getFailureCode() {
        // 返回稳定失败类型
        return this.failureCode;
    }
}
