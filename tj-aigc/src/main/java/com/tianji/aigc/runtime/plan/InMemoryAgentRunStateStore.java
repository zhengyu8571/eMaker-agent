package com.tianji.aigc.runtime.plan;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** 仅在当前进程内保存活动运行，不支持重启恢复。 */
@Component
public class InMemoryAgentRunStateStore implements AgentRunStateStore {

    // 用 runId 隔离不同请求的状态
    private final ConcurrentHashMap<String, AgentRunState> states = new ConcurrentHashMap<>();

    @Override
    public void create(AgentRunState state) {
        // 同一 runId 只允许登记一次
        if (this.states.putIfAbsent(state.runId(), state) != null) {
            // 避免覆盖仍在执行的运行
            throw new IllegalStateException("Agent runId 已存在：" + state.runId());
        }
    }

    @Override
    public Optional<AgentRunState> find(String runId) {
        // 返回当前内存中的只读状态快照
        return Optional.ofNullable(this.states.get(runId));
    }

    @Override
    public AgentRunState update(String runId, UnaryOperator<AgentRunState> updater) {
        // 在同一 runId 上原子替换状态快照
        return this.states.compute(runId, (key, current) -> {
            // 已结束的运行不能继续更新
            if (current == null) {
                // 提示调用方运行已经不存在
                throw new IllegalStateException("Agent runId 不存在：" + key);
            }
            // 根据当前快照构造下一份快照
            return updater.apply(current);
        });
    }

    @Override
    public void remove(String runId) {
        // 请求结束后释放该运行占用的内存
        this.states.remove(runId);
    }
}
