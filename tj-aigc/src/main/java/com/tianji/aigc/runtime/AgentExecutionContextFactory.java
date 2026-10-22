package com.tianji.aigc.runtime;

import cn.hutool.core.util.IdUtil;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.capability.CapabilityResolver;
import com.tianji.aigc.config.AgentRuntimeProperties;
import com.tianji.aigc.runtime.evidence.DefaultEvidenceCollector;
import com.tianji.aigc.runtime.evidence.EvidenceSanitizer;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.skill.model.ActivatedSkill;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 为每次DIRECT请求创建互不共享的运行上下文。
 */
@Component
public class AgentExecutionContextFactory {

    // 负责按Skill白名单解析请求级能力
    private final CapabilityResolver capabilityResolver;

    // 负责清理写入证据的输入和输出
    private final EvidenceSanitizer evidenceSanitizer;

    // 保存运行预算的默认配置
    private final AgentRuntimeProperties properties;
    // 调试开关关闭时不改变原请求标识生成方式
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    public AgentExecutionContextFactory(CapabilityResolver capabilityResolver, EvidenceSanitizer evidenceSanitizer, AgentRuntimeProperties properties) {
        // 保存能力解析组件
        this.capabilityResolver = capabilityResolver;
        // 保存证据清理组件
        this.evidenceSanitizer = evidenceSanitizer;
        // 保存运行时配置
        this.properties = properties;
    }

    public AgentExecutionContext create(String sessionId, Long userId, ActivatedSkill skill) {
        // 为外部请求生成唯一标识
        var requestId = this.debugTrace == null ? null : this.debugTrace.currentId();
        // 没有调试请求时保持原随机ID
        if (requestId == null) {
            // 保持原有运行时标识语义
            requestId = IdUtil.fastSimpleUUID();
        }
        // 为具体运行生成唯一标识
        var runId = IdUtil.fastSimpleUUID();
        // 按Skill白名单生成本次能力视图
        var capabilities = this.capabilityResolver.resolve(skill);
        // 创建本次运行独享的证据收集器
        var evidenceCollector = new DefaultEvidenceCollector(this.evidenceSanitizer);
        // 创建本次运行独享的取消令牌
        var cancellationToken = new CancellationToken();
        // 计算本次运行的总截止时间
        var deadline = Instant.now().plus(this.properties.getTimeout());
        // 创建本次运行独享的资源预算
        var budget = new AgentBudget(this.properties.getMaxSteps(), this.properties.getMaxModelCalls(), this.properties.getMaxToolCalls(), this.properties.getMaxTokens(), this.properties.getMaxReplans(), deadline);
        // 返回完整且显式传递的运行上下文
        return new AgentExecutionContext(requestId, runId, sessionId, userId, skill.agentType(), skill, capabilities, evidenceCollector, cancellationToken, budget);
    }
}
