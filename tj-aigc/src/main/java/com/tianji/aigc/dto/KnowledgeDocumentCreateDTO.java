package com.tianji.aigc.dto;

import lombok.Builder;
import lombok.Value;

/**
 * 创建知识库文档和处理任务所需的数据。
 */
@Value
@Builder
public class KnowledgeDocumentCreateDTO {

    // 文档所属知识库ID
    Long libraryId;

    // 用户指定或从文件名推导的文档标题
    String documentName;

    // 上传时保留的原始文件名
    String originalFileName;

    // 统一后的文件类型
    String fileType;

    // 上传请求携带的内容类型
    String contentType;

    // 原文件在对象存储中的地址
    String storageKey;

    // 原文件字节数
    Long fileSize;

    // 原文件 SHA-256 哈希
    String fileHash;
}
