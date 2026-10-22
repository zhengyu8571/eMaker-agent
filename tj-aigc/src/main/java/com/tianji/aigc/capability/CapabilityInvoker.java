package com.tianji.aigc.capability;

import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityInvocation;
import com.tianji.aigc.capability.model.CapabilityResult;

/**
 * 统一能力调用入口，后续实现负责查找能力并执行权限校验。
 */
public interface CapabilityInvoker {

    CapabilityResult invoke(CapabilityInvocation invocation,
                            CapabilityCallContext context);
}
