package com.tianji.aigc.runtime.plan;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 通过无记忆、无工具的客户端整理最终答案。 */
@Component
public class SpringAiFinalAnswerGateway implements FinalAnswerGateway {

    // 独立于业务 Agent 和规划模型的回答客户端
    private final ChatClient finalAnswerChatClient;

    public SpringAiFinalAnswerGateway(@Qualifier("finalAnswerChatClient") ChatClient finalAnswerChatClient) {
        // 保存只负责回答的客户端
        this.finalAnswerChatClient = finalAnswerChatClient;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        // 只发送已有结果，不注册工具或 Advisor
        return this.finalAnswerChatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
    }
}
