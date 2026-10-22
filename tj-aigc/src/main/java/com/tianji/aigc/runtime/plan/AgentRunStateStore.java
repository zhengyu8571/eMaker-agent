package com.tianji.aigc.runtime.plan;

import java.util.Optional;
import java.util.function.UnaryOperator;

/** 保存当前进程内正在执行的 PLAN 状态。 */
public interface AgentRunStateStore {

    // 新建一次活动运行
    void create(AgentRunState state);

    // 读取一次活动运行
    Optional<AgentRunState> find(String runId);

    // 原子更新一次活动运行
    AgentRunState update(String runId, UnaryOperator<AgentRunState> updater);

    // 运行结束后清理内存状态
    void remove(String runId);
}
