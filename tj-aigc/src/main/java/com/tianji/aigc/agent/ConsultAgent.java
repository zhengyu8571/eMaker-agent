package com.tianji.aigc.agent;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.tools.CourseTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 课程咨询智能体：使用当前Skill授权的课程工具查询真实业务信息。
 */
@Component
@RequiredArgsConstructor
public class ConsultAgent extends AbstractAgent {

    private static final String DEFAULT_SYSTEM_MESSAGE = """
            你是天机学堂的课程顾问，负责回答课程内容、大纲、价格、有效期、讲师和适用人群等问题。
            回答必须以本次授权课程工具的查询结果为依据；没有依据时明确说明未查到，不得编造。
            当前时间是 {now}。涉及课程有效期时，请结合当前时间给出清晰说明。
            """;

    private final SystemPromptConfig systemPromptConfig;
    private final CourseTools courseTools;

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.CONSULT;
    }

    @Override
    public String systemMessage() {
        // 保留配置中心的角色描述
        var configuredPrompt = this.systemPromptConfig.getConsultAgentSystemMessage().get();
        // 明确当前实现边界，避免沿用旧提示猜测已检索到资料
        return (StrUtil.isBlank(configuredPrompt) ? DEFAULT_SYSTEM_MESSAGE : configuredPrompt) + "\n当前课程咨询仅使用本次Skill授权的课程工具，不会自动检索技术文档；没有真实查询结果就追问课程名称或ID。技术文档原文问答应由知识Agent处理，不得伪造资料或引用。";
    }

    @Override
    public List<Advisor> advisors() {
        // 不绕过Skill和Runtime直接检索全库，文档问答交给知识Agent
        return List.of();
    }

    @Override
    public Object[] tools() {
        return new Object[]{this.courseTools};
    }

    @Override
    public Map<String, Object> toolContext(String sessionId, String requestId) {
        return Map.of(Constant.REQUEST_ID, requestId);
    }

    @Override
    public Map<String, Object> systemMessageParams() {
        return Map.of("now", DateUtil.now());
    }
}
