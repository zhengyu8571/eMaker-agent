package com.tianji.aigc.runtime.plan;

/** 只整理已有证据，不执行检索或工具。 */
public interface FinalAnswerGateway {

    // 根据目标和已完成结果生成最终文本
    String generate(String systemPrompt, String userPrompt);
}
