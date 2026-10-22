package com.tianji.aigc.agent;

import com.tianji.aigc.enums.AgentTypeEnum;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 业务智能体注册表，在应用启动期完成注册和完整性校验。
 */
@Component
public class AgentRegistry {

    private final Map<AgentTypeEnum, Agent> agents;

    public AgentRegistry(List<Agent> agentList) {
        var registeredAgents = new EnumMap<AgentTypeEnum, Agent>(AgentTypeEnum.class);
        for (Agent agent : agentList) {
            var agentType = agent.getAgentType();
            if (agentType == null) {
                throw new IllegalStateException("智能体类型不能为空：" + agent.getClass().getName());
            }
            if (agentType == AgentTypeEnum.ROUTE) {
                throw new IllegalStateException("路由智能体不应注册为业务智能体：" + agent.getClass().getName());
            }
            var existingAgent = registeredAgents.putIfAbsent(agentType, agent);
            if (existingAgent != null) {
                throw new IllegalStateException("智能体类型重复注册：" + agentType
                        + "，冲突类：" + existingAgent.getClass().getName()
                        + "、" + agent.getClass().getName());
            }
        }

        for (AgentTypeEnum type : AgentTypeEnum.values()) {
            if (type != AgentTypeEnum.ROUTE && !registeredAgents.containsKey(type)) {
                throw new IllegalStateException("缺少业务智能体：" + type);
            }
        }
        this.agents = Collections.unmodifiableMap(registeredAgents);
    }

    public Optional<Agent> find(AgentTypeEnum type) {
        return Optional.ofNullable(this.agents.get(type));
    }

    public String buildCatalog() {
        return this.agents.keySet().stream()
                .map(type -> "- " + type.name() + "：" + type.getDesc())
                .collect(Collectors.joining("\n"));
    }
}
