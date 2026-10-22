package com.tianji.aigc.dto;

import com.tianji.aigc.enums.RetrievalSource;
import com.tianji.aigc.enums.RetrievalStage;

import java.util.Map;
import java.util.Set;

/**
 * 保存检索各阶段的耗时、数量和降级信息。
 */
public record RetrievalTrace(

        // 每个检索阶段的执行耗时
        Map<RetrievalStage, Long> elapsedMillis,

        // 各召回来源返回的Chunk数量
        Map<RetrievalSource, Integer> recalledCounts,

        // 因异常或关闭而跳过的阶段
        Set<RetrievalStage> degradedStages
) {

    public RetrievalTrace {
        // 将运行轨迹固定为只读快照
        elapsedMillis = elapsedMillis == null ? Map.of() : Map.copyOf(elapsedMillis);
        recalledCounts = recalledCounts == null ? Map.of() : Map.copyOf(recalledCounts);
        degradedStages = degradedStages == null ? Set.of() : Set.copyOf(degradedStages);
    }
}
