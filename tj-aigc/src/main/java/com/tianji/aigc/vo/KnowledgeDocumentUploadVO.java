package com.tianji.aigc.vo;

import com.tianji.aigc.enums.KnowledgeDocumentStatus;
import com.tianji.aigc.enums.KnowledgeTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentUploadVO {

    // 文档所属知识库ID
    private Long libraryId;

    // 新建文档ID
    private Long documentId;

    // 新建处理任务ID
    private Long taskId;

    // 文档标题
    private String documentName;

    // 上传时的原始文件名
    private String originalFileName;

    // 统一后的文件类型
    private String fileType;

    // 原文件字节数
    private Long fileSize;

    // 当前文档版本
    private Integer version;

    // 当前文档处理状态
    private KnowledgeDocumentStatus documentStatus;

    // 当前任务处理状态
    private KnowledgeTaskStatus taskStatus;
}
