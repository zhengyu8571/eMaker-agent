package com.tianji.aigc.dto;

/**
 * 保存某一路召回对一个Chunk的原始排名和分数。
 */
public record RetrievalScore(

        // 在当前召回结果中的排名，从1开始
        int rank,

        // 召回引擎返回的原始分数
        double score
) {
}
