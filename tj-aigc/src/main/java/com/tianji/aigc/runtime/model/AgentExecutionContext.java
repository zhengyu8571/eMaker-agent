package com.tianji.aigc.runtime.model;

import com.tianji.aigc.capability.model.ResolvedCapabilities;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.runtime.AgentBudget;
import com.tianji.aigc.runtime.CancellationToken;
import com.tianji.aigc.runtime.evidence.EvidenceCollector;
import com.tianji.aigc.skill.model.ActivatedSkill;

/**
 * 显式保存一次Agent运行共享的身份、权限、预算和证据组件。
 */
public record AgentExecutionContext(

        // 标识一次外部聊天请求
        String requestId,

        // 标识一次具体Agent运行
        String runId,

        // 标识当前聊天会话
        String sessionId,

        // 标识当前登录用户
        Long userId,

        // 标识本次运行使用的业务Agent
        AgentTypeEnum agentType,

        // 保存本次运行激活的Skill快照
        ActivatedSkill skill,

        // 保存经过Skill白名单裁剪的能力视图
        ResolvedCapabilities capabilities,

        // 收集本次运行产生的可信证据
        EvidenceCollector evidenceCollector,

        // 在线程之间传递本次运行的取消状态
        CancellationToken cancellationToken,

        // 限制本次运行可以消耗的资源
        AgentBudget budget
) {

    public AgentExecutionContext {
        // 清理并校验请求标识
        requestId = requireText(requestId, "Agent运行的requestId不能为空");
        // 清理并校验运行标识
        runId = requireText(runId, "Agent运行的runId不能为空");
        // 清理并校验会话标识
        sessionId = requireText(sessionId, "Agent运行的sessionId不能为空");
        // 校验当前用户标识
        if (userId == null) {
            // 拒绝缺少用户身份的运行
            throw new IllegalArgumentException("Agent运行的userId不能为空");
        }
        // 校验业务Agent类型
        if (agentType == null) {
            // 拒绝缺少Agent类型的运行
            throw new IllegalArgumentException("Agent运行的agentType不能为空");
        }
        // 校验激活的Skill快照
        if (skill == null) {
            // 拒绝缺少Skill范围的运行
            throw new IllegalArgumentException("Agent运行的Skill不能为空");
        }
        // 校验Skill与业务Agent边界一致
        if (skill.agentType() != agentType) {
            // 阻止错误Skill进入其他业务Agent
            throw new IllegalArgumentException("Agent运行的Skill与Agent类型不一致");
        }
        // 校验请求级能力视图
        if (capabilities == null) {
            // 拒绝缺少能力白名单的运行
            throw new IllegalArgumentException("Agent运行的Capabilities不能为空");
        }
        // 校验证据收集组件
        if (evidenceCollector == null) {
            // 拒绝无法记录证据的运行
            throw new IllegalArgumentException("Agent运行的EvidenceCollector不能为空");
        }
        // 校验取消令牌
        if (cancellationToken == null) {
            // 拒绝无法取消的运行
            throw new IllegalArgumentException("Agent运行的CancellationToken不能为空");
        }
        // 校验运行预算
        if (budget == null) {
            // 拒绝没有资源边界的运行
            throw new IllegalArgumentException("Agent运行的Budget不能为空");
        }
    }

    private static String requireText(String value, String message) {
        // 拒绝空白文本
        if (value == null || value.isBlank()) {
            // 抛出对应的字段校验异常
            throw new IllegalArgumentException(message);
        }
        // 返回去除首尾空白后的文本
        return value.strip();
    }
}
