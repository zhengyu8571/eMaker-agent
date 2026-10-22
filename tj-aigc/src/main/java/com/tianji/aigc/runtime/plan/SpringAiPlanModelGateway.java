package com.tianji.aigc.runtime.plan;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/** 使用无会话记忆的 Spring AI ChatClient 生成结构化计划。 */
@Component
public class SpringAiPlanModelGateway implements PlanModelGateway {

    // 规划过程不挂载业务记忆、RAG 或工具
    private final ChatClient planningChatClient;
    // 只接受 ExecutionPlan 对应的结构化 JSON
    private final BeanOutputConverter<ExecutionPlan> converter;

    public SpringAiPlanModelGateway(@Qualifier("planningChatClient") ChatClient planningChatClient) {
        // 保存独立规划客户端
        this.planningChatClient = planningChatClient;
        // 使用项目已采用的 Spring AI 结构化输出转换器
        this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<ExecutionPlan>() {
        });
    }

    @Override
    public ExecutionPlan generate(String systemPrompt, String userPrompt) {
        // 为模型附上计划 JSON 的格式约束
        var formattedPrompt = userPrompt + "\n\n只输出以下格式的 JSON：\n" + this.converter.getFormat();
        // 调用无正式会话记忆的规划客户端
        var content = this.planningChatClient.prompt().system(systemPrompt).user(formattedPrompt).call().content();
        // 空结果无法形成计划
        if (content == null || content.isBlank()) {
            // 允许 Planner 针对格式错误修复一次
            throw new PlanModelOutputException("模型没有返回执行计划");
        }
        // 从可能的代码块中截取 JSON 对象
        var start = content.indexOf('{');
        // 定位 JSON 对象结尾
        var end = content.lastIndexOf('}');
        // 缺少完整对象时拒绝解析
        if (start < 0 || end <= start) {
            // 不把模型原始文本暴露给上层
            throw new PlanModelOutputException("模型未返回合法 JSON 对象");
        }
        try {
            // 将 JSON 转成第一步定义的强类型计划
            return this.converter.convert(content.substring(start, end + 1));
        } catch (RuntimeException exception) {
            // 将字段或 JSON 解析失败交给一次修复流程
            throw new PlanModelOutputException("模型计划结构无法解析", exception);
        }
    }
}
