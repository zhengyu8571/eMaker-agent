package com.tianji.aigc.agent;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ExecutionMode;

/**
 * 路由智能体对执行模式、业务Agent和Skill的一次结构化选择。
 */
public record RoutingDecision(

        // 执行模式为直接回答或多步规划
        ExecutionMode executionMode,

        // 负责回答的业务Agent
        AgentTypeEnum agentType,

        // 与目标Agent匹配的Skill ID
        String skillId,

        // 模型对本次路由选择的自评值
        double confidence,

        // 信息不足时要求用户补充问题
        boolean needClarification
) {
}
