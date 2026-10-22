package com.tianji.aigc.dto;

import lombok.Builder;
import lombok.Value;

/**
 * 文档分块使用的元数据。
 */
@Value
@Builder
public class KnowledgeDocumentChunkContext {

    // 供检索过滤使用的知识库稳定标识
    String libraryKey;

    // 文档所属知识库ID
    Long libraryId;

    // 当前分块所属文档ID
    Long documentId;

    // 当前分块所属文档版本
    Integer documentVersion;

    // 当前分块所属文档标题
    String documentTitle;
}
