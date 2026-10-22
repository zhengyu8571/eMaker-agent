package com.tianji.aigc.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 在线程之间安全传递一次运行的取消状态。
 */
public class CancellationToken {

    // 保存第一次成功写入的取消原因
    private final AtomicReference<String> cancellationReason = new AtomicReference<>();

    public boolean cancel(String reason) {
        // 清理并校验取消原因
        var normalizedReason = requireText(reason, "取消原因不能为空");
        // 只保留第一次取消操作的原因
        return this.cancellationReason.compareAndSet(null, normalizedReason);
    }

    public boolean isCancelled() {
        // 存在取消原因代表本次运行已经取消
        return this.cancellationReason.get() != null;
    }

    public String reason() {
        // 返回当前保存的取消原因
        return this.cancellationReason.get();
    }

    public void throwIfCancelled() {
        // 读取当前取消原因
        var reason = this.cancellationReason.get();
        // 未取消时允许继续执行
        if (reason == null) {
            // 结束本次状态检查
            return;
        }
        // 阻止已经取消的运行继续执行
        throw new AgentExecutionCancelledException(reason);
    }

    private static String requireText(String value, String message) {
        // 拒绝空白文本
        if (value == null || value.isBlank()) {
            // 抛出对应的字段校验异常
            throw new IllegalArgumentException(message);
        }
        // 返回去除首尾空白后的文本
        return value.strip();
    }
}
