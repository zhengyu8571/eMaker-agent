package com.tianji.aigc.runtime.plan;

/** 接收不包含模型推理和能力原始结果的进度事件。 */
@FunctionalInterface
public interface AgentProgressListener {

    // 默认监听器保持现有非流式执行行为
    AgentProgressListener NOOP = progress -> {
    };

    // 将一个安全进度事件交给上层传输
    void onProgress(AgentProgress progress);
}
