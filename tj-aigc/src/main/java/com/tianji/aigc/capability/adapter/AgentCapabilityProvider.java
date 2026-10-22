package com.tianji.aigc.capability.adapter;

import com.tianji.aigc.agent.AgentRegistry;
import com.tianji.aigc.capability.Capability;
import com.tianji.aigc.capability.CapabilityProvider;
import com.tianji.aigc.enums.AgentTypeEnum;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 为全部业务Agent生成统一能力，路由Agent仍只负责选择执行目标。
 */
@Component
public class AgentCapabilityProvider implements CapabilityProvider {

    private final List<Capability> capabilities;

    public AgentCapabilityProvider(ObjectProvider<AgentRegistry> agentRegistryProvider) {
        // 启动时只注册业务Agent类型，不提前实例化Agent
        this.capabilities = Arrays.stream(AgentTypeEnum.values())
                .filter(type -> type != AgentTypeEnum.ROUTE)
                .sorted(Comparator.comparing(Enum::name))
                // 真正执行能力时才读取已初始化的Agent注册表
                .map(type -> new AgentCapabilityAdapter(type, () -> agentRegistryProvider.getObject().find(type)
                        .orElseThrow(() -> new IllegalStateException("缺少业务智能体：" + type))))
                .map(Capability.class::cast)
                .toList();
    }

    @Override
    public List<Capability> capabilities() {
        return this.capabilities;
    }
}
