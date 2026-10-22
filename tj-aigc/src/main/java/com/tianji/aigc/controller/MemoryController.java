package com.tianji.aigc.controller;

import com.tianji.aigc.memory.model.EnhancedMemory.Preference;
import com.tianji.aigc.service.EnhancedMemoryService;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 用户只能查看与清除自己的摘要和偏好，不开放模型猜测写入。 */
@RestController
@RequestMapping("/memory")
@RequiredArgsConstructor
public class MemoryController {
    // 由业务层管理增强记忆
    private final EnhancedMemoryService memory;

    @GetMapping("/preferences")
    public Map<String, Preference> preferences() {
        // 返回原话与来源，不暴露内部版本和清除标记
        return this.memory.preferences(userId()).preferences();
    }

    @DeleteMapping("/preferences")
    public void clearPreferences(@RequestParam(value = "key", required = false) String key) {
        // 不接受外部传入其他用户标识
        this.memory.clearPreferences(userId(), key);
    }

    @GetMapping("/summary")
    public Map<String, String> summary(@RequestParam("sessionId") String sessionId) {
        // 摘要按当前用户与指定会话隔离
        return Map.of("summary", this.memory.conversationSummary(userId(), sessionId));
    }

    @DeleteMapping("/summary")
    public void clearSummary(@RequestParam("sessionId") String sessionId) {
        // 只删除增强摘要和待整理轮次，不删除原聊天记录
        this.memory.clearConversation(userId(), sessionId);
    }

    private Long userId() {
        // 身份来源于已有鉴权链
        var userId = UserContext.getUser();
        // 匿名请求不能共享或查看任何用户记忆
        if (userId == null || userId <= 0) {
            // 沿用项目未登录错误语义
            throw new UnauthorizedException("请登录后管理个人记忆");
        }
        // 返回服务器确认的用户身份
        return userId;
    }
}
