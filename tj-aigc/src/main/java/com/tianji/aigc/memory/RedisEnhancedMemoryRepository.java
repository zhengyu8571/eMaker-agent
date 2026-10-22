package com.tianji.aigc.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.EnhancedMemoryProperties;
import com.tianji.aigc.memory.model.EnhancedMemory.Conversation;
import com.tianji.aigc.memory.model.EnhancedMemory.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 用 Redis 条件更新保护会话摘要和用户偏好。 */
@Repository
public class RedisEnhancedMemoryRepository implements EnhancedMemoryRepository {
    // 先核对所有版本，再统一写入并设置过期时间
    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>("""
            for i = 1, #KEYS do
                local current = redis.call('GET', KEYS[i])
                local version = ''
                if current then version = cjson.decode(current).version end
                if version ~= ARGV[(i - 1) * 2 + 1] then return 0 end
            end
            for i = 1, #KEYS do
                redis.call('SET', KEYS[i], ARGV[(i - 1) * 2 + 2], 'PX', ARGV[#KEYS * 2 + 1])
            end
            return 1
            """, Long.class);
    // 复用现有 Redis 连接
    private final StringRedisTemplate redis;
    // 使用项目的 JSON 配置
    private final ObjectMapper mapper;
    // 读取增强数据保存期限
    private final EnhancedMemoryProperties properties;

    public RedisEnhancedMemoryRepository(StringRedisTemplate redis, ObjectMapper mapper, EnhancedMemoryProperties properties) {
        // 保存 Redis 操作组件
        this.redis = redis;
        // 保存 JSON 转换器
        this.mapper = mapper;
        // 保存记忆配置
        this.properties = properties;
    }

    @Override
    public Conversation findConversation(Long userId, String sessionId) {
        // 会话键同时包含可信用户和会话标识
        var json = this.redis.opsForValue().get(conversationKey(userId, sessionId));
        // 未保存时返回空快照
        return json == null ? Conversation.empty() : read(json, Conversation.class);
    }

    @Override
    public Profile findProfile(Long userId) {
        // 用户偏好不包含会话维度
        var json = this.redis.opsForValue().get(profileKey(userId));
        // 新用户不假造偏好
        return json == null ? Profile.empty() : read(json, Profile.class);
    }

    @Override
    public boolean saveConversation(Long userId, String sessionId, String expectedVersion, Conversation next) {
        // 只有版本仍相同时才能追加或清除
        return accepted(this.redis.execute(CAS, List.of(conversationKey(userId, sessionId)), expectedVersion, json(next), ttl()));
    }

    @Override
    public boolean saveProfile(Long userId, String expectedVersion, Profile next) {
        // 清除偏好也保留新版本，阻止旧模型结果复活
        return accepted(this.redis.execute(CAS, List.of(profileKey(userId)), expectedVersion, json(next), ttl()));
    }

    @Override
    public boolean saveUpdate(Long userId, String sessionId, String expectedConversationVersion, String expectedProfileVersion, Conversation conversation, Profile profile) {
        // 相同用户的两个键使用相同 Redis Cluster 槽位
        return accepted(this.redis.execute(CAS, List.of(conversationKey(userId, sessionId), profileKey(userId)), expectedConversationVersion, json(conversation), expectedProfileVersion, json(profile), ttl()));
    }

    private String conversationKey(Long userId, String sessionId) {
        // 不允许缺少会话标识或无界键名
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 200) {
            // 拒绝无法隔离的会话
            throw new IllegalArgumentException("记忆会话标识不合法");
        }
        try {
            // 固定长度的哈希避免会话文本进入 Redis 键名
            var hash = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            // 用户标识作为槽标签，摘要与偏好可一起原子提交
            return profileKey(userId) + ":session:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            // 当前 JDK 必须提供 SHA-256
            throw new IllegalStateException("无法生成记忆会话键", exception);
        }
    }

    private String profileKey(Long userId) {
        // 用户身份只能由服务端提供
        if (userId == null || userId <= 0) {
            // 不为匿名身份创建共享记忆
            throw new IllegalArgumentException("记忆用户标识不合法");
        }
        // 与原 CHAT 和知识库索引使用不同前缀
        return "AI:MEMORY:{" + userId + "}:profile";
    }

    private String ttl() {
        // 数据期限至少一秒，防止无效过期参数
        return Long.toString(Math.max(1000, this.properties.getRetention().toMillis()));
    }

    private boolean accepted(Long result) {
        // 仅脚本明确返回一才算成功
        return Long.valueOf(1).equals(result);
    }

    private String json(Object value) {
        try {
            // 只序列化固定的记忆契约
            return this.mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 不把用户正文写入错误信息
            throw new IllegalStateException("记忆数据无法序列化", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            // 按指定契约解析已保存的数据
            return this.mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            // 损坏数据由上层降级，不传播原始正文
            throw new IllegalStateException("记忆数据无法解析", exception);
        }
    }
}
