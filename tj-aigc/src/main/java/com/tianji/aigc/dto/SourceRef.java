package com.tianji.aigc.dto;

/**
 * 返回给前端的可信引用来源，只能由实际入选Chunk生成。
 */
public record SourceRef(

        // 回答正文中使用的引用编号，例如S1
        String citationId,

        // 引用对应的稳定Chunk ID
        String chunkId,

        // 引用所属知识库标识
        String libraryKey,

        // 引用所属文档ID
        Long documentId,

        // 引用所属文档版本
        Integer documentVersion,

        // 前端展示的文档标题
        String documentTitle,

        // 前端展示的章节标题
        String section
) {
}
