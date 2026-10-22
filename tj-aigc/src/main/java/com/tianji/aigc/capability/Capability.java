package com.tianji.aigc.capability;

import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;

import java.util.Map;

/**
 * 由Agent、RAG、本地工具和后续MCP适配器实现的统一能力接口。
 */
public interface Capability {

    /**
     * 返回能力注册和权限判断使用的稳定描述。
     */
    CapabilityDescriptor descriptor();

    /**
     * 使用结构化参数执行当前能力。
     */
    CapabilityResult execute(Map<String, Object> input,
                             CapabilityCallContext context);
}
