package com.tianji.aigc.service;

import com.tianji.aigc.vo.KnowledgeDocumentUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理知识库文档上传。
 */
public interface KnowledgeDocumentService {

    /**
     * 上传原文件并创建待处理任务。
     *
     * @param libraryId 知识库ID
     * @param documentName 可选文档标题
     * @param file 上传文件
     * @return 新建文档和任务信息
     */
    KnowledgeDocumentUploadVO upload(Long libraryId, String documentName, MultipartFile file);
}
