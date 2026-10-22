package com.tianji.aigc.service;

import com.tianji.aigc.memory.model.EnhancedMemory.Profile;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** 给聊天链提供参考记忆，并在正式回答完成后更新。 */
public interface EnhancedMemoryService {
    // 关闭时不改变 PLAN 的原有输入
    boolean enabled();

    // 当前用户和会话的低信任参考上下文
    String context(Long userId, String sessionId);

    // PLAN 不挂记忆 Advisor，显式加入近期正式原文
    String planningContext(Long userId, String sessionId, List<Message> history);

    // 管理接口读取实际摘要，关闭增强时也可查看
    String conversationSummary(Long userId, String sessionId);

    // 只给问题改写提供会话摘要，不混入全部偏好
    String summary(String conversationId);

    // 记录已完成的一轮原始问题与最终回答
    void recordCompleted(Long userId, String sessionId, String turnId, String question, String answer, long occurredAt);

    // 查看用户明确偏好及来源
    Profile preferences(Long userId);

    // 清除会话增强数据，阻止旧任务回写
    void clearConversation(Long userId, String sessionId);

    // 清除指定偏好，key 为空时清除所有白名单偏好
    void clearPreferences(Long userId, String key);
}
