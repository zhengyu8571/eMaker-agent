package com.tianji.aigc.runtime;

import cn.hutool.core.util.IdUtil;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.capability.CapabilityInvoker;
import com.tianji.aigc.capability.CapabilityRegistry;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.model.CapabilityAccessDeniedException;
import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityInvocation;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.runtime.model.CapabilityExecutionResult;
import com.tianji.aigc.runtime.model.CapabilityFailureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一执行经过Skill授权的能力，并落实预算、取消、超时和证据规则。
 */
@Component
public class AgentRuntime {

    // 设置取消检查的最长轮询间隔
    private static final long CANCELLATION_POLL_MILLIS = 100L;

    // 执行能力原有的校验和业务逻辑
    private final CapabilityInvoker capabilityInvoker;

    // 提供全局能力描述以记录拒绝证据
    private final CapabilityRegistry capabilityRegistry;

    // 隔离并限制可能阻塞的能力调用
    private final ExecutorService capabilityExecutor;
    // 调试记录不参与能力鉴权和预算判断
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    public AgentRuntime(CapabilityInvoker capabilityInvoker, CapabilityRegistry capabilityRegistry, @Qualifier("agentCapabilityExecutor") ExecutorService capabilityExecutor) {
        // 保存统一能力调用器
        this.capabilityInvoker = capabilityInvoker;
        // 保存全局能力目录
        this.capabilityRegistry = capabilityRegistry;
        // 保存能力专用线程池
        this.capabilityExecutor = capabilityExecutor;
    }

    public CapabilityExecutionResult invoke(String stepId, CapabilityInvocation invocation, AgentExecutionContext context) {
        // 没有调试组件时保持原执行路径
        if (this.debugTrace == null || !this.debugTrace.enabled()) {
            // 直接执行原运行时逻辑
            return invokeInternal(stepId, invocation, context);
        }
        // 用显式请求ID恢复PLAN或工具线程中的文件关联
        try (var ignored = this.debugTrace.scope(context == null ? "" : context.requestId())) {
            // 根据实际能力类型区分Tool、MCP和RAG
            var kind = invocation == null ? "CAPABILITY" : this.capabilityRegistry.find(invocation.capabilityKey()).map(capability -> capability.descriptor().kind().name()).orElse("CAPABILITY");
            // 调用前保存真实绑定参数
            var call = this.debugTrace.begin(kind, stepId + ":" + (invocation == null ? "unknown" : invocation.capabilityKey()), invocation == null ? null : invocation.input());
            try {
                // 不改原权限、预算、取消和异常映射
                var result = invokeInternal(stepId, invocation, context);
                // 失败也保存稳定错误码，不伪装成成功输出
                this.debugTrace.end(call, result.successful() ? result.result() : Map.of("failureCode", result.failureCode(), "message", String.valueOf(result.failureMessage())), result.successful() ? null : new IllegalStateException(result.failureMessage()));
                // 返回原能力结果
                return result;
            } catch (RuntimeException exception) {
                // 同步拒绝和参数错误也保存线索
                this.debugTrace.end(call, null, exception);
                // 不吞掉原业务异常
                throw exception;
            }
        }
    }

    private CapabilityExecutionResult invokeInternal(String stepId, CapabilityInvocation invocation, AgentExecutionContext context) {
        // 校验本次能力调用参数
        if (invocation == null || context == null) {
            // 拒绝缺少目标或上下文的调用
            throw new IllegalArgumentException("AgentRuntime调用参数和上下文不能为空");
        }
        // 清理并校验步骤标识
        var normalizedStepId = requireText(stepId, "AgentRuntime的stepId不能为空");
        // 为本次具体能力调用生成唯一标识
        var invocationId = IdUtil.fastSimpleUUID();
        // 读取全局目录中的能力描述
        var registeredCapability = this.capabilityRegistry.find(invocation.capabilityKey());
        // 未注册能力按越权请求处理
        if (registeredCapability.isEmpty()) {
            // 返回不暴露能力目录细节的拒绝结果
            return CapabilityExecutionResult.failure(invocationId, invocation.capabilityKey(), context.evidenceCollector().snapshot(), CapabilityFailureCode.ACCESS_DENIED, "当前Skill未授权该能力");
        }
        // 保存能力的稳定描述
        var descriptor = registeredCapability.get().descriptor();
        // 判断目标能力是否存在于请求级白名单
        if (context.capabilities().find(invocation.capabilityKey()).isEmpty()) {
            // 记录能力越权证据
            return failure(invocationId, normalizedStepId, descriptor, invocation, context, CapabilityFailureCode.ACCESS_DENIED, "当前Skill未授权该能力", Map.of("errorType", CapabilityAccessDeniedException.class.getSimpleName()));
        }
        // 记录本次调用开始时间
        var startedAt = System.nanoTime();
        try {
            // 阻止已取消运行继续提交能力任务
            context.cancellationToken().throwIfCancelled();
            // 扣减一个Agent执行步骤
            context.budget().reserveStep();
            // 按能力类型扣减对应调用预算
            reserveCapabilityBudget(descriptor.kind(), context);
            // 按输入文本长度扣减Token估算额度
            context.budget().reserveTokens(estimateInputTokens(invocation));
            // 取能力自身超时和运行总时限中的较小值
            var timeout = min(descriptor.timeout(), context.budget().remainingDuration());
            // 构造不依赖ThreadLocal的能力调用上下文
            var callContext = new CapabilityCallContext(context.requestId(), context.sessionId(), context.userId(), context.skill());
            // 在线程池中提交实际能力调用
            var observation = this.debugTrace == null ? null : this.debugTrace.currentObservation();
            // 能力内部的问题重写模型继承同一个请求文件
            var future = this.capabilityExecutor.submit(() -> {
                // 只在当前任务执行期间打开上下文
                try (var ignored = this.debugTrace == null ? null : this.debugTrace.scope(observation)) {
                    // 保持原能力调用与显式身份传递
                    return this.capabilityInvoker.invoke(invocation, callContext);
                }
            });
            // 等待结果期间持续检查取消和超时
            var result = await(future, timeout, context.cancellationToken());
            // 防止取消与能力完成同时发生时返回成功
            context.cancellationToken().throwIfCancelled();
            // 计算能力执行耗时
            var elapsedMillis = elapsedMillis(startedAt);
            // 记录成功结果和运行耗时
            context.evidenceCollector().record(invocationId, normalizedStepId, descriptor, invocation.input(), result, null, Map.of("elapsedMillis", elapsedMillis));
            // 返回能力结果和当前证据快照
            return CapabilityExecutionResult.success(invocationId, invocation.capabilityKey(), result, context.evidenceCollector().snapshot());
        } catch (RejectedExecutionException exception) {
            // 将线程池拥塞映射为稳定失败类型
            return failure(invocationId, normalizedStepId, descriptor, invocation, context, CapabilityFailureCode.EXECUTION_FAILED, "能力执行队列已满", failureMetadata(startedAt, exception));
        } catch (AgentBudgetExceededException exception) {
            // 将预算耗尽映射为稳定失败类型
            return failure(invocationId, normalizedStepId, descriptor, invocation, context, CapabilityFailureCode.BUDGET_EXHAUSTED, exception.getMessage(), failureMetadata(startedAt, exception));
        } catch (AgentExecutionCancelledException exception) {
            // 将主动取消映射为稳定失败类型
            return failure(invocationId, normalizedStepId, descriptor, invocation, context, CapabilityFailureCode.CANCELLED, exception.getMessage(), failureMetadata(startedAt, exception));
        } catch (TimeoutException exception) {
            // 将能力超时映射为稳定失败类型
            return failure(invocationId, normalizedStepId, descriptor, invocation, context, CapabilityFailureCode.TIMEOUT, "能力调用超过允许时间", failureMetadata(startedAt, exception));
        } catch (InterruptedException exception) {
            // 恢复当前线程的中断标记
            Thread.currentThread().interrupt();
            // 将线程中断同步为运行取消状态
            context.cancellationToken().cancel("Agent运行线程已中断");
            // 返回稳定的取消结果
            return failure(invocationId, normalizedStepId, descriptor, invocation, context, CapabilityFailureCode.CANCELLED, "Agent运行线程已中断", failureMetadata(startedAt, exception));
        } catch (ExecutionException exception) {
            // 读取能力线程抛出的原始异常
            var cause = exception.getCause() == null ? exception : exception.getCause();
            // 按原始异常类型生成稳定失败结果
            return mapExecutionFailure(invocationId, normalizedStepId, descriptor, invocation, context, startedAt, cause);
        }
    }

    private CapabilityResult await(Future<CapabilityResult> future, Duration timeout, CancellationToken cancellationToken) throws InterruptedException, ExecutionException, TimeoutException {
        // 将允许时长转换为单调时钟截止点
        var deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            // 循环等待并检查外部取消信号
            while (true) {
                // 阻止已经取消的运行继续等待
                cancellationToken.throwIfCancelled();
                // 计算本次剩余等待时间
                var remainingNanos = deadlineNanos - System.nanoTime();
                // 剩余时间耗尽时抛出超时
                if (remainingNanos <= 0L) {
                    // 通知调用方能力已经超时
                    throw new TimeoutException("能力调用超时");
                }
                // 限制单次等待时长以便及时发现取消
                var waitNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS));
                try {
                    // 在本轮等待窗口内获取能力结果
                    return future.get(waitNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException exception) {
                    // 本轮未完成时继续检查取消和总超时
                }
            }
        } catch (AgentExecutionCancelledException | InterruptedException | TimeoutException exception) {
            // 尝试中断不再需要的能力任务
            future.cancel(true);
            // 将原始控制异常交给上层映射
            throw exception;
        }
    }

    private void reserveCapabilityBudget(CapabilityKind kind, AgentExecutionContext context) {
        // Agent能力会触发新的模型回答
        if (kind == CapabilityKind.AGENT) {
            // 扣减一次模型调用预算
            context.budget().reserveModelCall();
            // Agent能力无需再扣减工具预算
            return;
        }
        // RAG和工具统一占用一次外部能力调用额度
        context.budget().reserveToolCall();
    }

    private CapabilityExecutionResult mapExecutionFailure(String invocationId, String stepId, CapabilityDescriptor descriptor, CapabilityInvocation invocation, AgentExecutionContext context, long startedAt, Throwable cause) {
        // 权限异常映射为越权失败
        if (cause instanceof CapabilityAccessDeniedException) {
            // 返回越权执行结果
            return failure(invocationId, stepId, descriptor, invocation, context, CapabilityFailureCode.ACCESS_DENIED, cause.getMessage(), failureMetadata(startedAt, cause));
        }
        // 预算异常映射为预算耗尽
        if (cause instanceof AgentBudgetExceededException) {
            // 返回预算耗尽结果
            return failure(invocationId, stepId, descriptor, invocation, context, CapabilityFailureCode.BUDGET_EXHAUSTED, cause.getMessage(), failureMetadata(startedAt, cause));
        }
        // 取消异常映射为主动取消
        if (cause instanceof AgentExecutionCancelledException) {
            // 返回取消结果
            return failure(invocationId, stepId, descriptor, invocation, context, CapabilityFailureCode.CANCELLED, cause.getMessage(), failureMetadata(startedAt, cause));
        }
        // 参数异常映射为输入错误
        if (cause instanceof IllegalArgumentException) {
            // 返回输入校验结果
            return failure(invocationId, stepId, descriptor, invocation, context, CapabilityFailureCode.INVALID_INPUT, safeMessage(cause), failureMetadata(startedAt, cause));
        }
        // 其他异常统一映射为能力执行失败
        return failure(invocationId, stepId, descriptor, invocation, context, CapabilityFailureCode.EXECUTION_FAILED, safeMessage(cause), failureMetadata(startedAt, cause));
    }

    private CapabilityExecutionResult failure(String invocationId, String stepId, CapabilityDescriptor descriptor, CapabilityInvocation invocation, AgentExecutionContext context, CapabilityFailureCode failureCode, String failureMessage, Map<String, Object> metadata) {
        // 记录失败调用的脱敏输入和运行信息
        context.evidenceCollector().record(invocationId, stepId, descriptor, invocation.input(), null, failureCode, metadata);
        // 返回失败类型和当前证据快照
        return CapabilityExecutionResult.failure(invocationId, invocation.capabilityKey(), context.evidenceCollector().snapshot(), failureCode, failureMessage);
    }

    private Map<String, Object> failureMetadata(long startedAt, Throwable exception) {
        // 保存耗时和异常类型但不保存可能含敏感值的消息
        return Map.of("elapsedMillis", elapsedMillis(startedAt), "errorType", exception.getClass().getSimpleName());
    }

    private long elapsedMillis(long startedAt) {
        // 将单调时钟差转换为非负毫秒数
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private int estimateInputTokens(CapabilityInvocation invocation) {
        // 使用结构化输入文本长度估算Token数量
        var inputLength = String.valueOf(invocation.input()).length();
        // 按约四个字符一个Token向上取整
        return Math.max(1, (inputLength + 3) / 4);
    }

    private Duration min(Duration first, Duration second) {
        // 比较两个允许时长并返回更严格的一个
        return first.compareTo(second) <= 0 ? first : second;
    }

    private String safeMessage(Throwable cause) {
        // 读取异常提供的原因文本
        var message = cause.getMessage();
        // 缺少原因时使用异常类型名称
        if (message == null || message.isBlank()) {
            // 返回稳定的异常类型名称
            return cause.getClass().getSimpleName();
        }
        // 限制错误文本长度避免响应无限膨胀
        return message.length() <= 300 ? message : message.substring(0, 300) + "...[truncated]";
    }

    private String requireText(String value, String message) {
        // 拒绝空白文本
        if (value == null || value.isBlank()) {
            // 抛出对应的字段校验异常
            throw new IllegalArgumentException(message);
        }
        // 返回去除首尾空白后的文本
        return value.strip();
    }
}
