package com.tianji.aigc.dto;

import java.util.List;

/**
 * 项目内部的完整检索结果，后续由Advisor和Evidence共同复用。
 */
public record RetrievalResult(

        // Query Rewrite之后实际执行检索的问题
        String normalizedQuery,

        // 经过融合、精排和裁剪后保留的Chunk
        List<RetrievedChunk> chunks,

        // 根据最终Chunk生成的可信引用来源
        List<SourceRef> sources,

        // 本次检索的阶段运行信息
        RetrievalTrace trace
) {

    public RetrievalResult {
        // 固定最终结果，防止下游修改检索证据
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
