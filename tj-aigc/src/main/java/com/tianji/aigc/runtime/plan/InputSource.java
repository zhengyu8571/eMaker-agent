package com.tianji.aigc.runtime.plan;

/** 标记计划步骤输入的来源。 */
public enum InputSource {
    // 从用户原始结构化输入读取
    USER_INPUT,
    // 从已完成步骤的结构化输出读取
    STEP_OUTPUT,
    // 使用计划中的固定值
    CONSTANT
}
