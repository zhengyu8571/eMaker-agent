package com.tianji.aigc.dto;

import java.util.List;

/**
 * 保存精排结果和本次是否发生降级。
 */
public record RerankOutcome(

        // 精排成功时为重排结果，降级时为原RRF结果
        List<RetrievedChunk> chunks,

        // true表示本次未能使用Rerank结果
        boolean degraded
) {

    public RerankOutcome {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static RerankOutcome success(List<RetrievedChunk> chunks) {
        return new RerankOutcome(chunks, false);
    }

    public static RerankOutcome degraded(List<RetrievedChunk> fallbackChunks) {
        return new RerankOutcome(fallbackChunks, true);
    }
}
