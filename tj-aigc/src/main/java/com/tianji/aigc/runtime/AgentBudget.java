package com.tianji.aigc.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限制一次Agent运行可以消耗的步骤、调用、Token和总时间。
 */
public class AgentBudget {

    // 保存剩余可执行步骤数
    private final AtomicInteger remainingSteps;

    // 保存剩余模型调用次数
    private final AtomicInteger remainingModelCalls;

    // 保存剩余重规划次数
    private final AtomicInteger remainingReplans;

    // 保存剩余工具调用次数
    private final AtomicInteger remainingToolCalls;

    // 保存剩余Token估算额度
    private final AtomicInteger remainingTokens;

    // 保存本次运行的总截止时间
    private final Instant deadline;

    public AgentBudget(int maxSteps,
                       int maxModelCalls,
                       int maxToolCalls,
                       int maxTokens,
                       Instant deadline) {
        // 兼容现有调用，默认最多重规划一次
        this(maxSteps, maxModelCalls, maxToolCalls, maxTokens, 1, deadline);
    }

    public AgentBudget(int maxSteps, int maxModelCalls, int maxToolCalls, int maxTokens, int maxReplans, Instant deadline) {
        // 校验最大步骤数
        requirePositive(maxSteps, "最大步骤数必须大于0");
        // 校验最大模型调用次数
        requirePositive(maxModelCalls, "最大模型调用次数必须大于0");
        // 允许显式关闭重规划
        if (maxReplans < 0) {
            // 拒绝负数重规划额度
            throw new IllegalArgumentException("最大重规划次数不能小于0");
        }
        // 校验最大工具调用次数
        requirePositive(maxToolCalls, "最大工具调用次数必须大于0");
        // 校验最大Token额度
        requirePositive(maxTokens, "最大Token额度必须大于0");
        // 校验总截止时间
        if (deadline == null) {
            // 拒绝缺少截止时间的预算
            throw new IllegalArgumentException("Agent执行截止时间不能为空");
        }
        // 初始化剩余步骤数
        this.remainingSteps = new AtomicInteger(maxSteps);
        // 初始化剩余模型调用次数
        this.remainingModelCalls = new AtomicInteger(maxModelCalls);
        // 初始化剩余重规划次数
        this.remainingReplans = new AtomicInteger(maxReplans);
        // 初始化剩余工具调用次数
        this.remainingToolCalls = new AtomicInteger(maxToolCalls);
        // 初始化剩余Token额度
        this.remainingTokens = new AtomicInteger(maxTokens);
        // 保存本次运行的总截止时间
        this.deadline = deadline;
    }

    public void reserveStep() {
        // 先确认本次运行仍在总时间内
        checkTime();
        // 原子扣减一个执行步骤
        consume(this.remainingSteps, 1, "Agent执行步骤预算已耗尽");
    }

    public void reserveModelCall() {
        // 先确认本次运行仍在总时间内
        checkTime();
        // 原子扣减一次模型调用
        consume(this.remainingModelCalls, 1, "Agent模型调用预算已耗尽");
    }

    public void reserveReplan() {
        // 重规划也受总截止时间限制
        checkTime();
        // 原子扣减一次重规划额度
        consume(this.remainingReplans, 1, "Agent重规划预算已耗尽");
    }

    public void reserveToolCall() {
        // 先确认本次运行仍在总时间内
        checkTime();
        // 原子扣减一次工具调用
        consume(this.remainingToolCalls, 1, "Agent工具调用预算已耗尽");
    }

    public void reserveTokens(int estimatedTokens) {
        // 校验本次预留的Token数量
        requirePositive(estimatedTokens, "预留Token数量必须大于0");
        // 先确认本次运行仍在总时间内
        checkTime();
        // 原子扣减本次预估使用的Token数量
        consume(this.remainingTokens, estimatedTokens, "Agent Token预算已耗尽");
    }

    public void checkTime() {
        // 判断当前时间是否已经到达总截止时间
        if (!Instant.now().isBefore(this.deadline)) {
            // 阻止超时运行继续消耗其他资源
            throw new AgentBudgetExceededException("Agent总执行时间预算已耗尽");
        }
    }

    public Duration remainingDuration() {
        // 固定本次计算使用的当前时间
        var now = Instant.now();
        // 判断当前时间是否已经到达总截止时间
        if (!now.isBefore(this.deadline)) {
            // 阻止超时运行继续等待能力结果
            throw new AgentBudgetExceededException("Agent总执行时间预算已耗尽");
        }
        // 计算固定时刻到总截止时间的剩余时长
        return Duration.between(now, this.deadline);
    }

    public int remainingSteps() {
        // 返回当前剩余步骤数
        return this.remainingSteps.get();
    }

    public int remainingModelCalls() {
        // 返回当前剩余模型调用次数
        return this.remainingModelCalls.get();
    }

    public int remainingReplans() {
        // 返回后端控制的剩余重规划次数
        return this.remainingReplans.get();
    }

    public int remainingToolCalls() {
        // 返回当前剩余工具调用次数
        return this.remainingToolCalls.get();
    }

    public int remainingTokens() {
        // 返回当前剩余Token额度
        return this.remainingTokens.get();
    }

    public Instant deadline() {
        // 返回本次运行的总截止时间
        return this.deadline;
    }

    private void consume(AtomicInteger counter, int amount, String message) {
        // 循环处理并发预算扣减
        while (true) {
            // 读取扣减前的剩余额度
            var current = counter.get();
            // 判断剩余额度是否足够
            if (current < amount) {
                // 拒绝超出预算的资源申请
                throw new AgentBudgetExceededException(message);
            }
            // 计算扣减后的剩余额度
            var updated = current - amount;
            // 原子更新成功后结束扣减
            if (counter.compareAndSet(current, updated)) {
                // 结束本次预算扣减
                return;
            }
        }
    }

    private static void requirePositive(int value, String message) {
        // 拒绝零和负数额度
        if (value <= 0) {
            // 抛出对应的预算校验异常
            throw new IllegalArgumentException(message);
        }
    }
}
