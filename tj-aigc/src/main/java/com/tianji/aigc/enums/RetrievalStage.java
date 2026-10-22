package com.tianji.aigc.enums;

/**
 * 项目内部定义的知识库检索阶段。
 */
public enum RetrievalStage {

    // 将上下文问题改写成独立问题
    QUERY_REWRITE,

    // 从Redis向量库召回
    VECTOR_RECALL,

    // 从Elasticsearch召回
    BM25_RECALL,

    // 合并两路召回结果
    RRF_FUSION,

    // 使用模型重新排序
    RERANK,

    // 按Token预算裁剪上下文
    CONTEXT_PRUNE
}
