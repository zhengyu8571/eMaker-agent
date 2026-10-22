package com.tianji.aigc.agent;

import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.SkillFeatureProperties;
import com.tianji.aigc.config.PlannerProperties;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ExecutionMode;
import com.tianji.aigc.skill.SkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * 路由智能体。使用独立、无记忆的 ChatClient，路由过程不会污染正式会话。
 */
@Component
public class RouteAgent {

    // 所有路由提示词都追加相同的意图边界
    private static final String INTENT_BOUNDARY = """
            补充判定边界：
            依据当前问题真正要求完成的事情分类，不按孤立关键词分类。
            文档中的技术概念、练习步骤、验收口令或原文事实问答属于KNOWLEDGE；提问中出现“步骤”不代表需要PLAN。
            “不查询课程”“不推测课程价格”等否定约束不是课程咨询意图，不能据此选择CONSULT。
            只有明确咨询具体课程业务信息才选择CONSULT。MCP专用Skill仅用于当前问题明确要求MCP或远程课程工具的请求。
            历史偏好和Skill目录只作参考，不得把目录中的MCP字样当作用户本次要求。
            """;

    private static final String DEFAULT_ROUTE_PROMPT = """
            你是天机学堂的请求路由器，只负责选择最合适的业务智能体，不回答用户的业务问题。
            路由规则：
            1. RECOMMEND：用户希望根据自身情况获得课程或学习路径推荐。
            2. CONSULT：用户咨询具体课程的价格、内容、大纲、老师、适用人群等信息。
            3. BUY：用户明确表达购买、报名、下单或确认订单的意图。
            4. KNOWLEDGE：用户希望讲解 Java、MySQL、Redis、Spring 等技术知识。
            信息不足或意图含糊时，将 needClarification 设为 true。
            confidence 必须是 0 到 1 之间的数字。不要输出分析过程。
            """;
    private static final String DEFAULT_SKILL_ROUTE_PROMPT = DEFAULT_ROUTE_PROMPT + """
            从可用Skill中选择一个与目标Agent匹配的skillId；没有明确细分需求时选择该Agent的默认Skill。
            executionMode 当前只能返回 DIRECT，不能返回 PLAN。
            """;
    // PLAN 开启时只在明显需要多步决策的请求中使用
    private static final String DEFAULT_PLAN_ROUTE_PROMPT = DEFAULT_ROUTE_PROMPT + """
            从可用Skill中选择一个与目标Agent匹配的skillId；没有明确细分需求时选择该Agent的默认Skill。
            executionMode 可以返回 DIRECT 或 PLAN。
            简单讲解、单门课程咨询、直接推荐或购买请求返回 DIRECT。
            同时包含多个目标或约束，并需要跨能力调用、根据中间结果决定后续动作时才返回 PLAN。
            需要知识检索并查询真实课程的综合学习规划，选择 KNOWLEDGE 和 study-plan Skill。
            不确定是否需要多步执行时返回 DIRECT。不要输出推理过程。
            """;

    private final ChatClient routingChatClient;
    private final SystemPromptConfig systemPromptConfig;
    private final AgentRegistry agentRegistry;
    private final SkillRegistry skillRegistry;
    private final SkillFeatureProperties skillFeatureProperties;
    // 决定是否允许真正返回 PLAN
    private final PlannerProperties plannerProperties;
    private final BeanOutputConverter<RoutingDecision> converter;
    private final BeanOutputConverter<LegacyRouteResult> legacyConverter;

    public RouteAgent(@Qualifier("routingChatClient") ChatClient routingChatClient,
                      SystemPromptConfig systemPromptConfig,
                      AgentRegistry agentRegistry,
                      SkillRegistry skillRegistry,
                      SkillFeatureProperties skillFeatureProperties,
                      PlannerProperties plannerProperties) {
        this.routingChatClient = routingChatClient;
        this.systemPromptConfig = systemPromptConfig;
        this.agentRegistry = agentRegistry;
        this.skillRegistry = skillRegistry;
        this.skillFeatureProperties = skillFeatureProperties;
        // 保存 PLAN 功能开关
        this.plannerProperties = plannerProperties;
        this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<RoutingDecision>() {
        });
        this.legacyConverter = new BeanOutputConverter<>(new ParameterizedTypeReference<LegacyRouteResult>() {
        });
    }

    public RoutingDecision route(String question) {
        if (!this.skillFeatureProperties.isEnabled()) {
            return routeWithoutSkill(question);
        }
        var systemPrompt = this.resolveSystemPrompt(true);

        var content = this.routingChatClient.prompt()
                .system(systemPrompt + "\n" + INTENT_BOUNDARY
                        + "\n当前可用业务智能体：\n" + this.agentRegistry.buildCatalog()
                        + "\n当前可用Skill：\n" + this.skillRegistry.buildCatalog())
                .user(question + "\n\n请严格按以下格式返回：\n" + this.converter.getFormat())
                .call()
                .content();

        var decision = this.converter.convert(extractJson(content));
        validate(decision);
        // 模型返回未知或跨Agent Skill时，统一降级为目标Agent默认Skill
        var activatedSkill = this.skillRegistry.resolve(
                decision.skillId(), decision.agentType());
        return new RoutingDecision(
                decision.executionMode(),
                decision.agentType(),
                activatedSkill.id(),
                decision.confidence(),
                decision.needClarification());
    }

    private RoutingDecision routeWithoutSkill(String question) {
        var content = this.routingChatClient.prompt()
                .system(this.resolveSystemPrompt(false) + "\n" + INTENT_BOUNDARY
                        + "\n当前可用业务智能体：\n" + this.agentRegistry.buildCatalog())
                .user(question + "\n\n请严格按以下格式返回：\n"
                        + this.legacyConverter.getFormat())
                .call()
                .content();
        var legacyResult = this.legacyConverter.convert(extractJson(content));
        var decision = new RoutingDecision(
                ExecutionMode.DIRECT,
                legacyResult.agentType(),
                null,
                legacyResult.confidence(),
                legacyResult.needClarification());
        validate(decision);
        return decision;
    }

    /**
     * 旧版 Nacos 提示词要求只返回纯枚举，与结构化输出冲突，因此只接受已升级的配置。
     */
    private String resolveSystemPrompt(boolean skillEnabled) {
        // PLAN 开启时使用明确的多步选择规则，避免旧 Nacos 提示词只允许 DIRECT
        if (skillEnabled && this.plannerProperties.isEnabled()) {
            // 返回阶段七的安全默认路由规则
            return DEFAULT_PLAN_ROUTE_PROMPT;
        }
        var configuredPrompt = this.systemPromptConfig.getRouteAgentSystemMessage().get();
        if (StrUtil.isBlank(configuredPrompt)
                || !configuredPrompt.contains("agentType")
                || !configuredPrompt.contains("confidence")
                || !configuredPrompt.contains("needClarification")) {
            return skillEnabled ? DEFAULT_SKILL_ROUTE_PROMPT : DEFAULT_ROUTE_PROMPT;
        }
        if (skillEnabled
                && (!configuredPrompt.contains("skillId")
                || !configuredPrompt.contains("executionMode"))) {
            return DEFAULT_SKILL_ROUTE_PROMPT;
        }
        return configuredPrompt;
    }

    private String extractJson(String content) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("路由模型返回空结果");
        }
        var start = content.indexOf('{');
        var end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("路由模型未返回 JSON：" + content);
        }
        return content.substring(start, end + 1);
    }

    private void validate(RoutingDecision decision) {
        if (decision == null || decision.agentType() == null) {
            throw new IllegalStateException("路由结果缺少目标智能体");
        }
        if (decision.agentType() == AgentTypeEnum.ROUTE) {
            throw new IllegalStateException("路由结果不能再次指向路由智能体");
        }
        // 功能开关关闭时必须维持阶段六 DIRECT 基线
        if (decision.executionMode() == ExecutionMode.PLAN && !this.plannerProperties.isEnabled()) {
            // 拒绝错误返回的 PLAN 决策
            throw new IllegalStateException("PLAN未开启，只允许DIRECT执行模式");
        }
        // 缺少执行模式不能默认为 PLAN 或 DIRECT
        if (decision.executionMode() == null) {
            // 要求路由模型明确选择模式
            throw new IllegalStateException("路由结果缺少执行模式");
        }
        if (!Double.isFinite(decision.confidence())
                || decision.confidence() < 0d
                || decision.confidence() > 1d) {
            throw new IllegalStateException("路由置信度必须在 0 到 1 之间");
        }
    }

    /**
     * 关闭Skill时只要求模型返回阶段一已有的三项路由字段。
     */
    private record LegacyRouteResult(
            AgentTypeEnum agentType,
            double confidence,
            boolean needClarification
    ) {
    }
}
