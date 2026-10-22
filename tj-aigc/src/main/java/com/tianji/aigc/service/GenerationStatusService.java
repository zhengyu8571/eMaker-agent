package com.tianji.aigc.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 管理流式回答的生成状态，供所有业务智能体共享。
 */
@Service
@RequiredArgsConstructor
public class GenerationStatusService {

    private static final String GENERATE_STATUS_KEY = "GENERATE_STATUS";

    private final StringRedisTemplate stringRedisTemplate;

    public void start(String sessionId) {
        this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY).put(sessionId, "true");
    }

    public void stop(String sessionId) {
        this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY).delete(sessionId);
    }

    public boolean isGenerating(String sessionId) {
        return this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY).get(sessionId) != null;
    }
}
