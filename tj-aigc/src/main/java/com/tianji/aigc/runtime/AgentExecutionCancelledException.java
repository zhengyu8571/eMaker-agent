package com.tianji.aigc.runtime;

/**
 * 表示当前运行已经被用户或系统取消。
 */
public class AgentExecutionCancelledException extends RuntimeException {

    public AgentExecutionCancelledException(String message) {
        // 保存本次运行的取消原因
        super(message);
    }
}
