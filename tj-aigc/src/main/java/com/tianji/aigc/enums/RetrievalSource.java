package com.tianji.aigc.enums;

/**
 * 项目内部定义的知识库召回来源。
 */
public enum RetrievalSource {

    // Redis向量相似度召回
    VECTOR,

    // Elasticsearch关键词召回
    BM25
}
