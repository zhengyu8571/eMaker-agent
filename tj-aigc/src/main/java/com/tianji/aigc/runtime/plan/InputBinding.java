package com.tianji.aigc.runtime.plan;

/** 将一个明确的来源字段绑定到能力输入字段。 */
public record InputBinding(String targetPath, InputSource source, String sourceStepId, String sourcePath, Object constantValue) {
}
