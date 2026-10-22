package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 会话摘要与明确用户偏好的边界配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.memory.enhanced")
public class EnhancedMemoryProperties {
    // 默认启用增强记忆，关闭时保留原聊天链
    private boolean enabled = true;
    // 累积十二轮后压缩较早的正式对话
    private int summaryThresholdTurns = 12;
    // 摘要后仍保留至少六轮近期原文
    private int recentTurns = 6;
    // 一次摘要最多处理十二轮
    private int summaryBatchTurns = 12;
    // 一次提取最多处理六条用户消息
    private int preferenceBatchTurns = 6;
    // 增强记录最多暂存一百轮，原 ChatMemory 不受影响
    private int maxPendingTurns = 100;
    // 超长问题不进入增强记忆，超长回答明确截取
    private int maxTurnChars = 12000;
    // 会话摘要的最大字符数
    private int maxSummaryChars = 3000;
    // 每条明确偏好的最大字符数
    private int maxPreferenceChars = 300;
    // 一次记忆模型输入的 JSON 字符上限
    private int maxModelInputChars = 64000;
    // PLAN 读取近期原文的最大 JSON 字符数
    private int maxRecentContextChars = 6000;
    // 每次记忆模型调用的等待上限
    private Duration timeout = Duration.ofSeconds(10);
    // 增强数据独立设置保存期限
    private Duration retention = Duration.ofDays(90);
}
