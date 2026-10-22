package com.tianji.aigc.capability;

import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.model.CapabilityAccessDeniedException;
import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityInvocation;
import com.tianji.aigc.capability.model.CapabilityResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 统一完成能力查找、二次鉴权、参数校验和实际调用。
 */
@Component
@RequiredArgsConstructor
public class DefaultCapabilityInvoker implements CapabilityInvoker {

    // Capability 解析器
    private final CapabilityResolver capabilityResolver;

    // Capability 入参校验器
    private final CapabilityInputValidator inputValidator;

    /**
     * 执行一次 Capability 调用。
     */
    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation, CapabilityCallContext context) {

        // 调用参数和上下文不能为空
        if (invocation == null || context == null) {
            throw new IllegalArgumentException("Capability调用参数和上下文不能为空");
        }

        // 根据当前 Skill 重新计算允许使用的 Capability
        var capability = this.capabilityResolver.resolve(context.skill())
                // 获取当前要执行的 Capability
                .require(invocation.capabilityKey());

        // 再次校验 Agent 调用边界
        verifyTopLevelAgent(capability, context);

        // 校验 Capability 输入参数
        this.inputValidator.validate(capability.descriptor(), invocation.input());

        // 真正执行 Capability
        return capability.execute(invocation.input(), context);
    }

    /**
     * 校验当前请求是否越权调用其他 Agent。
     */
    private void verifyTopLevelAgent(Capability capability, CapabilityCallContext context) {

        // 不是 Agent 类型则无需校验
        if (capability.descriptor().kind() != CapabilityKind.AGENT) {
            return;
        }

        // 生成当前 Skill 应该对应的 Agent Key
        var expectedKey = CapabilityKeys.agent(context.skill().agentType());

        // 实际 Agent Key 不一致，说明越权访问
        if (!capability.descriptor().key().equals(expectedKey)) {
            throw new CapabilityAccessDeniedException(
                    "当前请求不能执行其他Agent能力：" + capability.descriptor().key());
        }
    }
}