package com.tianji.aigc.agent;

import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.advisor.HybridKnowledgeAdvisor;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识讲解智能体：根据知识库讲解 Java、MySQL、Redis、Spring 等技术问题。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeAgent extends AbstractAgent {

    private static final String DEFAULT_SYSTEM_MESSAGE = """
            你是天机学堂的技术讲师，负责讲解 Java、MySQL、Redis、Spring 等 IT 技术知识。
            回答时先给结论，再解释原理，最后给出必要的示例、常见误区或使用场景。
            优先依据检索到的知识资料回答；资料不足时明确说明边界，不得伪造资料或事实。
            """;

    private final SystemPromptConfig systemPromptConfig;
    private final HybridKnowledgeAdvisor hybridKnowledgeAdvisor;

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.KNOWLEDGE;
    }

    @Override
    public String systemMessage() {
        var configuredPrompt = this.systemPromptConfig.getKnowledgeAgentSystemMessage().get();
        return StrUtil.isBlank(configuredPrompt) ? DEFAULT_SYSTEM_MESSAGE : configuredPrompt;
    }

    @Override
    public List<Advisor> advisors() {
        // 使用项目内部Advisor接入完整混合检索链
        return List.of(this.hybridKnowledgeAdvisor);
    }
}
