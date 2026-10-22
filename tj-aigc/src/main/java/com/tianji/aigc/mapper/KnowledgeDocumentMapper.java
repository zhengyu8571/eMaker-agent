package com.tianji.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.aigc.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 读写知识库文档记录。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 仅更新仍指向当前任务和版本的文档。
     */
    @Update("""
            UPDATE kb_document
            SET status = #{targetStatus}, update_time = NOW()
            WHERE id = #{documentId}
              AND current_task_id = #{taskId}
              AND version = #{documentVersion}
              AND deleted = 0
            """)
    int updateCurrentTaskStatus(@Param("documentId") Long documentId,
                                @Param("taskId") Long taskId,
                                @Param("documentVersion") Integer documentVersion,
                                @Param("targetStatus") int targetStatus);
}
