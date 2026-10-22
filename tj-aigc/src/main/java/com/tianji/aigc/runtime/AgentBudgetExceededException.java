package com.tianji.aigc.runtime;

/**
 * 表示当前运行已经耗尽某项执行预算。
 */
public class AgentBudgetExceededException extends RuntimeException {

    public AgentBudgetExceededException(String message) {
        // 保存预算耗尽的具体原因
        super(message);
    }
}
