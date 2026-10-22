package com.tianji.aigc.capability.model;

import com.tianji.aigc.skill.model.ActivatedSkill;

/**
 * 显式传递一次能力调用所需的请求身份和Skill范围。
 */
public record CapabilityCallContext(

        // 当前HTTP或SSE请求标识
        String requestId,

        // 当前聊天会话标识
        String sessionId,

        // 当前登录用户标识
        Long userId,

        // 本次请求已经激活的Skill只读快照
        ActivatedSkill skill
) {

    public CapabilityCallContext {
        requestId = requireText(requestId, "Capability调用的requestId不能为空");
        sessionId = requireText(sessionId, "Capability调用的sessionId不能为空");
        if (userId == null) {
            throw new IllegalArgumentException("Capability调用的userId不能为空");
        }
        if (skill == null) {
            throw new IllegalArgumentException("Capability调用的Skill不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
