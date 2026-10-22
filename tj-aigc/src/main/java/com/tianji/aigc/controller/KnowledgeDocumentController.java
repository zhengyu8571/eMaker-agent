package com.tianji.aigc.controller;

import com.tianji.aigc.service.KnowledgeDocumentService;
import com.tianji.aigc.vo.KnowledgeDocumentUploadVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/knowledge-libraries")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    // 处理知识库文档上传业务
    private final KnowledgeDocumentService knowledgeDocumentService;

    // 接收指定知识库的 multipart 文档
    @PostMapping(value = "/{libraryId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeDocumentUploadVO upload(@PathVariable("libraryId") Long libraryId,
                                            @RequestParam(value = "documentName", required = false) String documentName,
                                            @RequestParam("file") MultipartFile file) {
        // 将知识库ID、文档标题和文件交给业务层
        return this.knowledgeDocumentService.upload(libraryId, documentName, file);
    }
}
