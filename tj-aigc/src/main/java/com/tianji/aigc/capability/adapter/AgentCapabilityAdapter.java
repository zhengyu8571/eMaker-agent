package com.tianji.aigc.capability.adapter;

import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.capability.Capability;
import com.tianji.aigc.capability.CapabilityKeys;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.common.utils.UserContext;
import com.tianji.aigc.enums.AgentTypeEnum;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 将现有业务Agent包装成统一能力，供后续执行内核复用。
 */
public class AgentCapabilityAdapter implements Capability {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
            """;
    private static final String OUTPUT_SCHEMA = """
            {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}
            """;

    private final Supplier<Agent> agentSupplier;
    private final CapabilityDescriptor descriptor;

    public AgentCapabilityAdapter(Agent agent) {
        // 兼容直接包装已有Agent的调用方式
        this(agent.getAgentType(), () -> agent);
    }

    public AgentCapabilityAdapter(AgentTypeEnum agentType, Supplier<Agent> agentSupplier) {
        // 延迟获取Agent，避免能力目录与Agent初始化互相等待
        this.agentSupplier = agentSupplier;
        this.descriptor = new CapabilityDescriptor(
                CapabilityKeys.agent(agentType),
                CapabilityKind.AGENT,
                agentType.getDesc(),
                INPUT_SCHEMA,
                OUTPUT_SCHEMA,
                SideEffectLevel.WRITE_REVERSIBLE,
                Duration.ofSeconds(90),
                false,
                false);
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return this.descriptor;
    }

    @Override
    public CapabilityResult execute(Map<String, Object> input,
                                    CapabilityCallContext context) {
        var query = requiredText(input, "query");
        var previousUserId = UserContext.getUser();
        try {
            // 复用现有Agent时显式还原调用用户，防止跨线程丢失身份
            UserContext.setUser(context.userId());
            // 到实际执行时才获取对应的业务Agent
            var answer = this.agentSupplier.get().process(query, context.sessionId(), context.skill());
            return CapabilityResult.of(Map.of("answer", answer));
        } finally {
            if (previousUserId == null) {
                UserContext.removeUser();
            } else {
                UserContext.setUser(previousUserId);
            }
        }
    }

    private String requiredText(Map<String, Object> input, String field) {
        var value = input == null ? null : input.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Agent能力参数" + field + "不能为空");
        }
        return text.strip();
    }
}
