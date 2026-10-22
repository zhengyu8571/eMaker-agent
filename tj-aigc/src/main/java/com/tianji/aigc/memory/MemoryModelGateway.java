package com.tianji.aigc.memory;

import com.tianji.aigc.memory.model.EnhancedMemory.ModelResult;

import java.util.Map;

/** 模型只提出变化，最终提交仍由业务层校验。 */
public interface MemoryModelGateway {
    // 根据指定的正式对话生成摘要与偏好变化
    ModelResult generate(Map<String, Object> input);
}
