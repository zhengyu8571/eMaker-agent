package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.dto.SourceRef;

import java.util.List;

/** 返回用户可见的最终文本和实际引用来源。 */
public record PlanFinalAnswer(String text, List<SourceRef> sources, boolean completed) {

    public PlanFinalAnswer {
        // 最终文本不能留空
        if (text == null || text.isBlank()) {
            // 避免生成空 DATA 事件
            throw new IllegalArgumentException("PLAN 最终回答不能为空");
        }
        // 固定对外引用列表
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
