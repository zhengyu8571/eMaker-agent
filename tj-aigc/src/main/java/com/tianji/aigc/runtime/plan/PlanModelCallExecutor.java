package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.config.PlannerProperties;
import com.tianji.aigc.debug.AiDebugTrace;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 限制PLAN模型等待，调用次数和Token仍由原调用方扣减。 */
@Component
public class PlanModelCallExecutor {
    // 模型等待与DAG步骤分池，避免互相占满线程
    private final ExecutorService executor;
    // 提供单次模型等待上限
    private final PlannerProperties properties;
    // 保留跨线程的原请求流水
    private final AiDebugTrace trace;

    public PlanModelCallExecutor(@Qualifier("agentPlanModelExecutor") ExecutorService executor, PlannerProperties properties, AiDebugTrace trace) {
        // 保存有界执行池
        this.executor = executor;
        // 保存等待上限
        this.properties = properties;
        // 测试可以不启用调试记录
        this.trace = trace;
    }

    public <T> T call(String stage, AgentExecutionContext context, Supplier<T> action) {
        // 提交前拒绝已取消的运行
        context.cancellationToken().throwIfCancelled();
        // 读取单次模型等待上限
        var timeout = this.properties.getModelCallTimeout();
        // 错误配置不能绕过等待限制
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // 不接受无限等待
            throw new IllegalArgumentException("PLAN模型等待上限必须大于0");
        }
        // 排队也计入等待，且不能超过总剩余时间
        var allowance = Math.min(timeout.toNanos(), context.budget().remainingDuration().toNanos());
        // 单次等待使用单调时钟
        var started = System.nanoTime();
        // 捕获原请求的观测链
        var observation = this.trace == null ? null : this.trace.currentObservation();
        // 只记录等待控制，正文仍由模型观察器保存
        var call = this.trace == null ? null : this.trace.begin("MODEL_WAIT", stage, Map.of("timeoutMillis", TimeUnit.NANOSECONDS.toMillis(allowance)));
        // 保存Future供超时和取消清理
        Future<T> future = null;
        try {
            // 队列满时直接拒绝，不在聊天线程执行
            future = this.executor.submit(() -> {
                // 跨线程模型记录继续归属原trace
                try (var ignored = this.trace == null ? null : this.trace.scope(observation)) {
                    // 不执行已经取消的排队任务
                    context.cancellationToken().throwIfCancelled();
                    // 排队期间也可能耗尽总预算
                    context.budget().checkTime();
                    // 超过单次上限的排队任务不再调用模型
                    if (System.nanoTime() - started >= allowance) {
                        // 不把过期任务当作新一次执行
                        throw new IllegalStateException("PLAN模型排队超时：" + stage);
                    }
                    // 仅执行原模型逻辑，不调用业务能力
                    return action.get();
                }
            });
            // 定期检查取消，不等满HTTP读取时间
            while (true) {
                // 用户停止后不再等待
                context.cancellationToken().throwIfCancelled();
                // 总截止时间优先于单次超时
                context.budget().checkTime();
                // 单次等待包含线程池排队
                var remaining = allowance - (System.nanoTime() - started);
                // 超时不当作JSON格式错误重试
                if (remaining <= 0) {
                    // 防止慢调用吃掉整次重规划时间
                    throw new IllegalStateException("PLAN模型调用超时：" + stage);
                }
                try {
                    // 最多一百毫秒重新检查取消
                    var result = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                    // 截止点附近到达的结果也要重新检查
                    if (System.nanoTime() - started >= allowance) {
                        // 迟到结果不能继续驱动计划
                        throw new IllegalStateException("PLAN模型调用超时：" + stage);
                    }
                    // 丢弃总截止时间之后到达的结果
                    context.budget().checkTime();
                    // 丢弃取消之后到达的结果
                    context.cancellationToken().throwIfCancelled();
                    // 记录等待正常结束
                    if (this.trace != null) {
                        // 不重复保存模型正文
                        this.trace.end(call, Map.of("completed", true), null);
                    }
                    // 返回原结果
                    return result;
                } catch (TimeoutException ignored) {
                    // 只重新检查状态，不重发模型请求
                } catch (ExecutionException exception) {
                    // 保留原格式错误类型供一次修复使用
                    if (exception.getCause() instanceof RuntimeException cause) {
                        // 原模型异常不能被当作成功
                        throw cause;
                    }
                    // 不在错误中附带模型正文
                    throw new IllegalStateException("PLAN模型调用失败：" + stage, exception.getCause());
                }
            }
        } catch (InterruptedException exception) {
            // 保留关闭或上层取消信号
            Thread.currentThread().interrupt();
            // 保存控制边界的中断原因
            if (this.trace != null) {
                // 模型原始异常由观察器另行记录
                this.trace.end(call, null, exception);
            }
            // 中断不触发计划格式修复
            throw new IllegalStateException("PLAN模型等待已中断：" + stage, exception);
        } catch (RuntimeException exception) {
            // 拒绝、超时和取消都记录实际原因
            if (this.trace != null) {
                // 保留原异常类型
                this.trace.end(call, null, exception);
            }
            // 由聊天入口映射安全提示
            throw exception;
        } finally {
            // 不保留仍排队或执行中的Future
            if (future != null && !future.isDone()) {
                // 只能约束本地，不能保证远端立即停止
                future.cancel(true);
            }
            // 及时移除取消的排队任务，释放等待位置
            if (future instanceof Runnable task && this.executor instanceof java.util.concurrent.ThreadPoolExecutor pool) {
                // 已执行完的任务移除操作无副作用
                pool.remove(task);
            }
        }
    }
}
