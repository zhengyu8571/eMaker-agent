package com.tianji.aigc.runtime;

import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存当前进程内正在执行的Agent运行。
 */
@Component
public class AgentRunRegistry {

    // 按运行标识保存活动上下文
    private final ConcurrentHashMap<String, AgentExecutionContext> runsById = new ConcurrentHashMap<>();

    // 按用户和会话保存当前活动上下文
    private final ConcurrentHashMap<String, AgentExecutionContext> runsBySession = new ConcurrentHashMap<>();

    public void register(AgentExecutionContext context) {
        // 校验准备注册的运行上下文
        if (context == null) {
            // 拒绝注册空运行
            throw new IllegalArgumentException("Agent运行上下文不能为空");
        }
        // 按运行标识注册上下文
        var duplicate = this.runsById.putIfAbsent(context.runId(), context);
        // 判断运行标识是否已经被占用
        if (duplicate != null) {
            // 阻止不同运行共用同一标识
            throw new IllegalStateException("Agent runId已经注册：" + context.runId());
        }
        // 计算用户会话的进程内唯一键
        var sessionKey = sessionKey(context.userId(), context.sessionId());
        // 原子替换同一用户会话的旧运行
        this.runsBySession.compute(sessionKey, (key, previous) -> {
            // 存在旧运行时先发出取消信号
            if (previous != null) {
                // 标记旧运行为同会话新请求替换
                previous.cancellationToken().cancel("同一会话已开始新的Agent运行");
                // 从运行索引移除旧上下文
                this.runsById.remove(previous.runId(), previous);
            }
            // 保存当前运行作为会话最新运行
            return context;
        });
    }

    public Optional<AgentExecutionContext> findByRunId(String runId) {
        // 空运行标识不会命中活动运行
        if (runId == null || runId.isBlank()) {
            // 返回空查询结果
            return Optional.empty();
        }
        // 返回运行标识对应的活动上下文
        return Optional.ofNullable(this.runsById.get(runId.strip()));
    }

    public Optional<AgentExecutionContext> findBySession(Long userId, String sessionId) {
        // 用户或会话缺失时不会命中活动运行
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            // 返回空查询结果
            return Optional.empty();
        }
        // 返回用户会话对应的最新活动上下文
        return Optional.ofNullable(this.runsBySession.get(sessionKey(userId, sessionId)));
    }

    public boolean cancelBySession(Long userId, String sessionId, String reason) {
        // 查找并取消当前用户会话的活动运行
        return findBySession(userId, sessionId).map(context -> context.cancellationToken().cancel(reason)).orElse(false);
    }

    public Optional<AgentExecutionContext> remove(String runId) {
        // 空运行标识没有可删除的上下文
        if (runId == null || runId.isBlank()) {
            // 返回空删除结果
            return Optional.empty();
        }
        // 先从运行标识索引移除上下文
        var removed = this.runsById.remove(runId.strip());
        // 没有命中时直接返回空结果
        if (removed == null) {
            // 返回空删除结果
            return Optional.empty();
        }
        // 计算被删除运行对应的用户会话键
        var sessionKey = sessionKey(removed.userId(), removed.sessionId());
        // 只在会话仍指向该运行时删除会话索引
        this.runsBySession.remove(sessionKey, removed);
        // 返回被删除的运行上下文
        return Optional.of(removed);
    }

    public int activeCount() {
        // 返回当前活动运行数量
        return this.runsById.size();
    }

    private String sessionKey(Long userId, String sessionId) {
        // 组合用户和会话避免跨用户误取消
        return userId + ":" + sessionId.strip();
    }
}
