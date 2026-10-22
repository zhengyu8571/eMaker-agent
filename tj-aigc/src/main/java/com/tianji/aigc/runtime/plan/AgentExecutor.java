package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.capability.model.CapabilityInvocation;
import com.tianji.aigc.runtime.AgentBudgetExceededException;
import com.tianji.aigc.runtime.AgentRuntime;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.runtime.model.CapabilityFailureCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 执行已校验的 DAG；能力调用统一交给 AgentRuntime。 */
@Slf4j
@Component
public class AgentExecutor {

    // 限制一份计划同时运行的步骤数
    private static final int MAX_PARALLEL_STEPS = 4;
    // 等待并行步骤时每次检查取消的间隔
    private static final long POLL_MILLIS = 100L;
    // 停止时给 AgentRuntime 留出记录失败证据的短暂窗口
    private static final long STOP_SETTLE_MILLIS = 500L;

    // 在执行前重复验证计划和当前授权
    private final PlanValidator validator;
    // 统一执行能力并负责预算、超时与证据
    private final AgentRuntime runtime;
    // 将明确的字段绑定变成能力输入
    private final PlanInputBinder inputBinder;
    // 保存本次请求的活动状态快照
    private final AgentRunStateStore stateStore;
    // 隔离并限制并行计划步骤
    private final ExecutorService stepExecutor;
    // 按 runId 隔离并发请求的进度监听器
    private final Map<String, AgentProgressListener> progressListeners = new ConcurrentHashMap<>();

    public AgentExecutor(PlanValidator validator, AgentRuntime runtime, PlanInputBinder inputBinder, AgentRunStateStore stateStore, @Qualifier("agentPlanStepExecutor") ExecutorService stepExecutor) {
        // 保存确定性计划校验器
        this.validator = validator;
        // 保存统一能力执行器
        this.runtime = runtime;
        // 保存输入字段绑定器
        this.inputBinder = inputBinder;
        // 保存活动运行状态存储
        this.stateStore = stateStore;
        // 保存专用有界线程池
        this.stepExecutor = stepExecutor;
    }

    public AgentRunState execute(ExecutionPlan plan, AgentExecutionContext context, Map<String, Object> userInput) {
        // 未订阅进度的调用保持原有执行语义
        return execute(plan, context, userInput, AgentProgressListener.NOOP);
    }

    public AgentRunState execute(ExecutionPlan plan, AgentExecutionContext context, Map<String, Object> userInput, AgentProgressListener listener) {
        // 再次确认计划未绕过权限和 Schema 校验
        this.validator.validate(plan, context, userInput);
        // 记录所有尚未运行的步骤
        var pending = new LinkedHashMap<String, PlanStep>();
        // 记录步骤结果并保持计划顺序
        var results = new LinkedHashMap<String, StepExecutionResult>();
        // 初始化每个步骤的状态
        for (var step : plan.steps()) {
            // 按稳定步骤 ID 保存计划
            pending.put(step.id().strip(), step);
            // 未开始的步骤保持 PENDING
            results.put(step.id().strip(), new StepExecutionResult(step.id().strip(), StepStatus.PENDING, null, null));
        }
        // 创建只存在于当前进程的运行快照
        this.stateStore.create(new AgentRunState(context.runId(), plan, results, context.evidenceCollector().snapshot(), context.budget(), 0));
        // 为本轮请求注册独立的安全进度回调
        this.progressListeners.put(context.runId(), listener == null ? AgentProgressListener.NOOP : listener);
        try {
            // 根据依赖分批执行并返回最终快照
            return run(pending, results, context, userInput == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(userInput)));
        } finally {
            // 清理本轮进度回调
            this.progressListeners.remove(context.runId());
            // 请求结束后清理内存状态，避免积累历史运行
            this.stateStore.remove(context.runId());
        }
    }

    public AgentRunState resume(ExecutionPlan plan, AgentRunState previous, AgentExecutionContext context, Map<String, Object> userInput) {
        // 未订阅进度的续跑保持原有执行语义
        return resume(plan, previous, context, userInput, AgentProgressListener.NOOP);
    }

    public AgentRunState resume(ExecutionPlan plan, AgentRunState previous, AgentExecutionContext context, Map<String, Object> userInput, AgentProgressListener listener) {
        // 续跑必须属于同一次 Agent 运行
        if (previous == null || !previous.runId().equals(context.runId()) || previous.budget() != context.budget()) {
            // 阻止跨请求复用步骤和预算
            throw new IllegalArgumentException("重规划状态与当前运行不一致");
        }
        // 找出上一版真正执行成功的步骤
        Set<String> reusable = previous.stepResults().entrySet().stream().filter(entry -> entry.getValue().status() == StepStatus.SUCCEEDED).map(Map.Entry::getKey).collect(Collectors.toSet());
        // 按剩余步骤预算重新校验且禁止修改已成功步骤
        this.validator.validateReplan(plan, context, userInput, previous);
        // 保存新版本尚需执行的步骤
        var pending = new LinkedHashMap<String, PlanStep>();
        // 保存旧版成功结果和新版等待结果
        var results = new LinkedHashMap<String, StepExecutionResult>();
        // 依次建立新计划的运行状态
        for (var step : plan.steps()) {
            // 复用已成功的步骤结果
            if (reusable.contains(step.id().strip())) {
                // 原结果保留给下游字段绑定
                results.put(step.id().strip(), previous.stepResults().get(step.id().strip()));
            } else {
                // 未成功步骤必须重新调度
                pending.put(step.id().strip(), step);
                // 将失败或跳过状态恢复为等待
                results.put(step.id().strip(), new StepExecutionResult(step.id().strip(), StepStatus.PENDING, null, null));
            }
        }
        // 新计划沿用原请求的证据收集器和预算
        this.stateStore.create(previous.withPlan(plan, results, context.evidenceCollector().snapshot(), previous.replanCount() + 1));
        // 为续跑注册当前请求的进度回调
        this.progressListeners.put(context.runId(), listener == null ? AgentProgressListener.NOOP : listener);
        try {
            // 只执行尚未成功的步骤
            return run(pending, results, context, userInput == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(userInput)));
        } finally {
            // 清理续跑进度回调
            this.progressListeners.remove(context.runId());
            // 续跑结束后仍清理活动状态
            this.stateStore.remove(context.runId());
        }
    }

    private AgentRunState run(Map<String, PlanStep> pending, Map<String, StepExecutionResult> results, AgentExecutionContext context, Map<String, Object> userInput) {
        // 只要还有未开始步骤就继续调度
        while (!pending.isEmpty()) {
            // 取消或总截止时间到达时终止本轮计划
            var stop = stopStatus(context);
            // 有停止原因时不再提交新步骤
            if (stop != null) {
                // 标记全部未开始步骤
                markPending(pending, results, context, stop, "计划已取消或达到总截止时间");
                // 返回当前完整快照
                return snapshot(context);
            }
            // 选择依赖全部成功的最多四个步骤
            var ready = readySteps(pending, results);
            // 没有可运行步骤代表前序失败或依赖未满足
            if (ready.isEmpty()) {
                // 将无法再运行的步骤显式标为跳过
                markPending(pending, results, context, StepStatus.SKIPPED, "依赖步骤未成功");
                // 返回失败或跳过后的快照
                return snapshot(context);
            }
            // 每批使用独立的完成队列收取结果
            CompletionService<StepExecutionResult> completion = new ExecutorCompletionService<>(this.stepExecutor);
            // 只由调度线程修改正在运行的步骤表
            var running = new LinkedHashMap<Future<StepExecutionResult>, PlanStep>();
            // 当前批次若有失败就不启动下一批
            var failed = false;
            // 提交当前无依赖阻塞的步骤
            for (var step : ready) {
                // 从等待集合移走当前步骤
                pending.remove(step.id().strip());
                // 先保存 RUNNING 状态供本次运行查看
                save(new StepExecutionResult(step.id().strip(), StepStatus.RUNNING, null, null), results, context);
                try {
                    // 固定上游结果快照，防止并发步骤互相读取
                    var completed = Map.copyOf(results);
                    // 在线程池里完成绑定与统一能力调用
                    var future = completion.submit(() -> executeStep(step, context, userInput, completed));
                    // 记录 Future 与原始步骤的对应关系
                    running.put(future, step);
                } catch (RejectedExecutionException exception) {
                    // 线程池拥塞时明确失败，不退到公共线程池
                    save(new StepExecutionResult(step.id().strip(), StepStatus.FAILED, null, "计划步骤线程池已满"), results, context);
                    // 不再提交后续计划步骤
                    failed = true;
                    // 结束本次提交
                    break;
                }
            }
            // 等待已提交步骤完成或收到终止信号
            while (!running.isEmpty()) {
                // 运行期间也要检查取消和总截止时间
                stop = stopStatus(context);
                // 停止时中断所有尚未完成的计划任务
                if (stop != null) {
                    try {
                        // 先等待 Runtime 返回并写入失败证据
                        settleRunning(completion, running, results, context, stop);
                    } catch (InterruptedException exception) {
                        // 恢复被中断线程的状态
                        Thread.currentThread().interrupt();
                        // 将线程中断传播给能力执行
                        context.cancellationToken().cancel("Agent计划线程已中断");
                        // 中断尚未完成的步骤
                        cancelRunning(running, results, context, StepStatus.CANCELLED);
                    }
                    // 标记仍未提交的步骤
                    markPending(pending, results, context, stop, "计划已取消或达到总截止时间");
                    // 返回停止时的证据快照
                    return snapshot(context);
                }
                try {
                    // 短轮询以及时响应取消
                    var future = completion.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    // 暂无完成步骤时继续检查状态
                    if (future == null) {
                        // 进入下一次轮询
                        continue;
                    }
                    // 找到完成的原始步骤
                    var step = running.remove(future);
                    // 收集成功、失败或异常结果
                    var result = completedResult(future, step);
                    // 保存步骤结果与当前证据
                    save(result, results, context);
                    // 任一步失败后不再启动新批次
                    failed |= result.status() != StepStatus.SUCCEEDED;
                } catch (InterruptedException exception) {
                    // 恢复执行线程的中断标记
                    Thread.currentThread().interrupt();
                    // 将线程中断传播给共享取消令牌
                    context.cancellationToken().cancel("Agent计划线程已中断");
                    // 中断本批正在运行的步骤
                    cancelRunning(running, results, context, StepStatus.CANCELLED);
                    // 标记本轮未提交的步骤
                    markPending(pending, results, context, StepStatus.CANCELLED, "Agent计划线程已中断");
                    // 返回取消后的状态
                    return snapshot(context);
                }
            }
            // 本批失败时停止后续调度
            if (failed) {
                // 下游和剩余独立步骤都不再启动
                markPending(pending, results, context, StepStatus.SKIPPED, "本轮已有步骤失败");
                // 返回当前失败状态
                return snapshot(context);
            }
        }
        // 所有步骤成功后返回最终状态
        return snapshot(context);
    }

    private List<PlanStep> readySteps(Map<String, PlanStep> pending, Map<String, StepExecutionResult> results) {
        // 保存本批次可以同时运行的步骤
        var ready = new ArrayList<PlanStep>();
        // 按计划原有顺序扫描等待步骤
        for (var step : pending.values()) {
            // 所有显式依赖成功后才可运行
            if (step.dependsOn().stream().allMatch(id -> results.get(id.strip()).status() == StepStatus.SUCCEEDED)) {
                // 加入本批可运行步骤
                ready.add(step);
            }
            // 单批最多占用四个计划线程
            if (ready.size() == MAX_PARALLEL_STEPS) {
                // 剩余步骤留给后续批次
                break;
            }
        }
        // 返回按计划顺序选出的步骤
        return ready;
    }

    private StepExecutionResult executeStep(PlanStep step, AgentExecutionContext context, Map<String, Object> userInput, Map<String, StepExecutionResult> results) {
        try {
            // 在能力执行前阻止已取消的运行
            context.cancellationToken().throwIfCancelled();
            // 从用户输入、上游输出或常量构造结构化参数
            var input = this.inputBinder.bind(step, userInput, results);
            // 仅向 Runtime 传递能力 key 和输入
            var invocation = new CapabilityInvocation(step.capabilityKey().strip(), input);
            // 将权限、预算、取消和证据交给现有执行内核
            var capabilityResult = this.runtime.invoke(step.id().strip(), invocation, context);
            // 成功的能力调用完成当前步骤
            if (capabilityResult.successful()) {
                // 保留能力执行结果供下游绑定
                return new StepExecutionResult(step.id().strip(), StepStatus.SUCCEEDED, capabilityResult, null);
            }
            // 取消结果要和普通执行失败区分
            var status = capabilityResult.failureCode() == CapabilityFailureCode.CANCELLED ? StepStatus.CANCELLED : StepStatus.FAILED;
            // 保存稳定失败类型和消息
            return new StepExecutionResult(step.id().strip(), status, capabilityResult, capabilityResult.failureMessage());
        } catch (RuntimeException exception) {
            // 已取消的运行不能误报为普通失败
            var status = context.cancellationToken().isCancelled() ? StepStatus.CANCELLED : StepStatus.FAILED;
            // 输入绑定或执行边界异常都让步骤有界终止
            return new StepExecutionResult(step.id().strip(), status, null, safeMessage(exception));
        }
    }

    private StepExecutionResult completedResult(Future<StepExecutionResult> future, PlanStep step) throws InterruptedException {
        try {
            // 读取后台线程的明确步骤结果
            return future.get();
        } catch (ExecutionException exception) {
            // 后台未捕获错误也转为步骤失败
            return new StepExecutionResult(step.id().strip(), StepStatus.FAILED, null, safeMessage(exception.getCause()));
        } catch (CancellationException exception) {
            // 已取消的后台任务保持取消状态
            return new StepExecutionResult(step.id().strip(), StepStatus.CANCELLED, null, "计划步骤已取消");
        }
    }

    private void settleRunning(CompletionService<StepExecutionResult> completion, Map<Future<StepExecutionResult>, PlanStep> running, Map<String, StepExecutionResult> results, AgentExecutionContext context, StepStatus stop) throws InterruptedException {
        // 使用单调时钟限制等待失败证据的时间
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_SETTLE_MILLIS);
        // 在短窗口内收集 Runtime 已完成的调用
        while (!running.isEmpty()) {
            // 计算剩余等待时长
            var remaining = deadline - System.nanoTime();
            // 超过短窗口就不再阻塞请求
            if (remaining <= 0L) {
                // 进入兜底中断
                break;
            }
            // 等待一条能力调用完成
            var future = completion.poll(remaining, TimeUnit.NANOSECONDS);
            // 等待窗口内没有结果时结束
            if (future == null) {
                // 进入兜底中断
                break;
            }
            // 找到对应的计划步骤
            var step = running.remove(future);
            // 保存 Runtime 返回的实际结果和证据
            save(completedResult(future, step), results, context);
        }
        // 仍在执行的能力必须收到取消信号
        if (!running.isEmpty()) {
            // 传播截止时间或用户取消
            context.cancellationToken().cancel("Agent计划已取消或达到总截止时间");
            // 中断无法及时结束的计划任务
            cancelRunning(running, results, context, stop);
        }
    }

    private void cancelRunning(Map<Future<StepExecutionResult>, PlanStep> running, Map<String, StepExecutionResult> results, AgentExecutionContext context, StepStatus status) {
        // 对本批每个尚未完成的步骤发送中断
        for (var entry : running.entrySet()) {
            // 请求后台任务停止
            entry.getKey().cancel(true);
            // 固定该步骤的终止状态
            save(new StepExecutionResult(entry.getValue().id().strip(), status, null, "计划已取消或达到总截止时间"), results, context);
        }
        // 清空本批的运行记录
        running.clear();
    }

    private void markPending(Map<String, PlanStep> pending, Map<String, StepExecutionResult> results, AgentExecutionContext context, StepStatus status, String reason) {
        // 给每个未启动步骤留下明确终态
        for (var step : pending.values()) {
            // 保存跳过、取消或截止状态
            save(new StepExecutionResult(step.id().strip(), status, null, reason), results, context);
        }
        // 清空本轮等待步骤
        pending.clear();
    }

    private void save(StepExecutionResult result, Map<String, StepExecutionResult> results, AgentExecutionContext context) {
        // 失败时只记录步骤标识和有限错误摘要
        if (result.status() == StepStatus.FAILED) {
            // 保留联调和生产排障需要的失败线索
            log.warn("PLAN 步骤失败，stepId={}，reason={}", result.stepId(), result.failureReason());
        }
        // 更新本次调度线程持有的结果表
        results.put(result.stepId(), result);
        // 原子写入当前内存中的状态和证据快照
        this.stateStore.update(context.runId(), current -> current.withStepResult(result, context.evidenceCollector().snapshot()));
        // 将步骤状态转成不含业务数据的进度事件
        var listener = this.progressListeners.get(context.runId());
        // 无监听器时不发送事件
        if (listener != null) {
            // 运行中代表步骤启动，其他状态代表步骤结束
            var type = result.status() == StepStatus.RUNNING ? PlanProgressType.STEP_STARTED : PlanProgressType.STEP_COMPLETED;
            // 只公开类型、步骤 ID 和状态
            try {
                // 尽力发送进度而不改变能力执行结果
                listener.onProgress(new AgentProgress(type, result.stepId(), result.status()));
            } catch (RuntimeException ignored) {
                // 进度消费者故障不应导致已完成步骤被重跑
            }
        }
    }

    private AgentRunState snapshot(AgentExecutionContext context) {
        // 最后同步一次证据快照
        return this.stateStore.update(context.runId(), current -> current.withEvidence(context.evidenceCollector().snapshot()));
    }

    private StepStatus stopStatus(AgentExecutionContext context) {
        // 主动取消优先于后续预算检查
        if (context.cancellationToken().isCancelled()) {
            // 用 CANCELLED 标记尚未完成的步骤
            return StepStatus.CANCELLED;
        }
        try {
            // 检查请求级总截止时间
            context.budget().checkTime();
            // 尚可继续调度
            return null;
        } catch (AgentBudgetExceededException exception) {
            // 总截止时间到达后不能再执行步骤
            // 已运行步骤按失败终止
            return StepStatus.FAILED;
        }
    }

    private String safeMessage(Throwable cause) {
        // 没有异常时返回稳定失败文本
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            // 避免返回空原因
            return "计划步骤执行失败";
        }
        // 限制错误文本长度
        var message = cause.getMessage();
        // 返回有界错误原因
        return message.length() <= 300 ? message : message.substring(0, 300) + "...[truncated]";
    }
}
