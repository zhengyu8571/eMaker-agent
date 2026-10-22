package com.tianji.aigc.runtime.plan;

import org.springframework.ai.chat.client.ChatClient;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/** 使用独立规划客户端返回结构化目标评估。 */
@Component
public class SpringAiGoalEvaluationGateway implements GoalEvaluationGateway {

    // 评估过程不接入正式记忆和业务工具
    private final ChatClient planningChatClient;
    // 将模型输出转换为明确的评估契约
    private final BeanOutputConverter<GoalEvaluation> converter;

    public SpringAiGoalEvaluationGateway(@Qualifier("planningChatClient") ChatClient planningChatClient) {
        // 保存无业务增强的客户端
        this.planningChatClient = planningChatClient;
        // 创建结构化输出转换器
        this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<GoalEvaluation>() {
        });
    }

    @Override
    public GoalEvaluation generate(String systemPrompt, String userPrompt) {
        // 限定模型返回结构化 JSON
        var prompt = userPrompt + "\n\n只输出以下格式的 JSON：\n" + this.converter.getFormat();
        // 获取模型原始评估结果
        var content = this.planningChatClient.prompt().system(systemPrompt).user(prompt).toolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "GOAL_EVALUATE")).call().content();
        // 空结果不能用于控制重规划
        if (content == null || content.isBlank()) {
            // 明确上报模型输出错误
            throw new PlanModelOutputException("模型没有返回目标评估");
        }
        // 定位 JSON 对象起点
        var start = content.indexOf('{');
        // 定位 JSON 对象终点
        var end = content.lastIndexOf('}');
        // 缺少完整对象时拒绝评估
        if (start < 0 || end <= start) {
            // 不接受自然语言作为控制信号
            throw new PlanModelOutputException("模型未返回合法评估 JSON");
        }
        try {
            // 将 JSON 变为强类型评估结果
            return this.converter.convert(content.substring(start, end + 1));
        } catch (RuntimeException exception) {
            // 格式错误不得被当作目标完成
            throw new PlanModelOutputException("模型评估结构无法解析", exception);
        }
    }
}
