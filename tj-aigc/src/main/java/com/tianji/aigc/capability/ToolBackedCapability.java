package com.tianji.aigc.capability;

import org.springframework.ai.tool.ToolCallback;

/**
 * 标识底层可以直接作为Spring AI工具暴露的Capability。
 */
public interface ToolBackedCapability extends Capability {

    ToolCallback toolCallback();
}
