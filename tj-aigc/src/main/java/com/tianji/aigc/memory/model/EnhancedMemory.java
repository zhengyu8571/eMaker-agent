package com.tianji.aigc.memory.model;

import java.util.List;
import java.util.Map;

/** 只保存正式对话、会话摘要和用户明确陈述，不保存执行证据。 */
public final class EnhancedMemory {
    private EnhancedMemory() {
        // 不创建契约容器实例
    }

//Conversation：某个用户的某个会话
//├─ summary：会话摘要
//├─ turns：List<Turn>，待处理正式问答
//├─ version：更新版本
//└─ generation：清除前后的代次
    // id 去重，发生时间用于避免旧请求覆盖新偏好
    public record Turn(String id, String question, String answer, long occurredAt, boolean preferencesProcessed) { }

    // version 用于条件更新，generation 用于阻止清除后的旧任务回写
    public record Conversation(String version, String generation, String summary, List<Turn> turns, long clearedAt) {
        public Conversation {
            // 固定当前对话快照
            turns = turns == null ? List.of() : List.copyOf(turns);
        }

        public Conversation(String version, String generation, String summary, List<Turn> turns) {
            // 未清除的会话从零开始记录
            this(version, generation, summary, turns, 0);
        }

        public static Conversation empty() {
            // 空版本表示 Redis 中尚无记录
            return new Conversation("", "", "", List.of(), 0);
        }
    }

//Profile：某个用户，跨会话共享
//├─ preferences：Map<字段名, Preference>
//├─ forgottenAt：Map<字段名, 清除时刻>
//└─ version：更新版本


    // 值保留用户原话，同时记录来源及用户发言时间
    public record Preference(String value, String sourceSessionId, String sourceTurnId, long updatedAt) { }

    // 同一用户的偏好可跨会话复用
    public record Profile(String version, Map<String, Preference> preferences, Map<String, Long> forgottenAt) {
        public Profile {
            // 防止调用方修改已读取的偏好
            preferences = preferences == null ? Map.of() : Map.copyOf(preferences);
            // 保留清除时刻，旧对话不能重新提取已删除偏好
            forgottenAt = forgottenAt == null ? Map.of() : Map.copyOf(forgottenAt);
        }

        public Profile(String version, Map<String, Preference> preferences) {
            // 首次创建偏好时没有清除记录
            this(version, preferences, Map.of());
        }

        public static Profile empty() {
            // 尚未保存偏好的用户返回空集合
            return new Profile("", Map.of(), Map.of());
        }
    }


//ModelResult：模型提出的更新建议
//├─ summary：建议的新摘要
//└─ preferences：List<PreferenceChange>

    // 模型只能提出白名单字段、操作、用户原话和来源轮次
    public record PreferenceChange(String key, String operation, String quote, String turnId) { }

    // 一个模型调用同时处理需要更新的摘要和偏好
    public record ModelResult(String summary, List<PreferenceChange> preferences) { }
}
