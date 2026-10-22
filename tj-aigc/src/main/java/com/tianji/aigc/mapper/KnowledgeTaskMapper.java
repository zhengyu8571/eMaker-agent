package com.tianji.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.aigc.entity.KnowledgeTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 读写文档处理任务记录。
 */
@Mapper
public interface KnowledgeTaskMapper extends BaseMapper<KnowledgeTask> {

    /**
     * 查询尚未确认投递的待处理任务ID。
     *
     * @param pendingStatus 待处理状态值
     * @param limit 最大查询数量
     * @return 按创建时间排列的任务ID
     */
    @Select("""
            SELECT id
            FROM kb_task
            WHERE status = #{pendingStatus}
              AND sent_at IS NULL
            ORDER BY create_time ASC
            LIMIT #{limit}
            """)
    List<Long> selectPendingDispatchTaskIds(@Param("pendingStatus") int pendingStatus,
                                            @Param("limit") int limit);

    /**
     * 记录 RabbitMQ 已经确认的投递信息。
     *
     * @param taskId 任务ID
     * @param pendingStatus 待处理状态值
     * @param messageId RabbitMQ消息ID
     * @param sentAt 投递确认时间
     * @return 更新行数
     */
    @Update("""
            UPDATE kb_task
            SET message_id = #{messageId}, sent_at = #{sentAt}, update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{pendingStatus}
              AND sent_at IS NULL
            """)
    int markDispatched(@Param("taskId") Long taskId,
                       @Param("pendingStatus") int pendingStatus,
                       @Param("messageId") String messageId,
                       @Param("sentAt") LocalDateTime sentAt);

    /**
     * 使用状态条件抢占一个等待处理的任务。
     */
    @Update("""
            UPDATE kb_task
            SET status = #{processingStatus},
                total_chunks = 0,
                processed_chunks = 0,
                failed_chunks = 0,
                error_code = NULL,
                error_message = NULL,
                started_at = COALESCE(started_at, #{startedAt}),
                finished_at = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status IN (#{pendingStatus}, #{retryStatus})
            """)
    int tryMarkProcessing(@Param("taskId") Long taskId,
                          @Param("pendingStatus") int pendingStatus,
                          @Param("retryStatus") int retryStatus,
                          @Param("processingStatus") int processingStatus,
                          @Param("startedAt") LocalDateTime startedAt);

    /**
     * Broker 重新投递未确认消息时重新接管处理中任务。
     */
    @Update("""
            UPDATE kb_task
            SET total_chunks = 0,
                processed_chunks = 0,
                failed_chunks = 0,
                error_code = NULL,
                error_message = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{processingStatus}
            """)
    int reclaimProcessing(@Param("taskId") Long taskId,
                          @Param("processingStatus") int processingStatus);

    /**
     * 保存解析后得到的分块总数。
     */
    @Update("""
            UPDATE kb_task
            SET total_chunks = #{totalChunks}, update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{processingStatus}
            """)
    int recordTotalChunks(@Param("taskId") Long taskId,
                          @Param("processingStatus") int processingStatus,
                          @Param("totalChunks") int totalChunks);

    /**
     * 累加已经同时写入两个索引的分块数。
     */
    @Update("""
            UPDATE kb_task
            SET processed_chunks = processed_chunks + #{count}, update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{processingStatus}
            """)
    int addProcessedChunks(@Param("taskId") Long taskId,
                           @Param("processingStatus") int processingStatus,
                           @Param("count") int count);

    /**
     * 将处理完成的任务标记为索引成功。
     */
    @Update("""
            UPDATE kb_task
            SET status = #{indexedStatus},
                total_chunks = #{totalChunks},
                processed_chunks = #{totalChunks},
                failed_chunks = 0,
                error_code = NULL,
                error_message = NULL,
                finished_at = #{finishedAt},
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{processingStatus}
            """)
    int markIndexed(@Param("taskId") Long taskId,
                    @Param("processingStatus") int processingStatus,
                    @Param("indexedStatus") int indexedStatus,
                    @Param("totalChunks") int totalChunks,
                    @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 记录一次失败并把任务放回重试状态。
     */
    @Update("""
            UPDATE kb_task
            SET status = #{retryStatus},
                retry_count = retry_count + 1,
                total_chunks = #{totalChunks},
                processed_chunks = #{processedChunks},
                failed_chunks = #{failedChunks},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{processingStatus}
              AND retry_count < max_retries
            """)
    int markRetry(@Param("taskId") Long taskId,
                  @Param("processingStatus") int processingStatus,
                  @Param("retryStatus") int retryStatus,
                  @Param("totalChunks") int totalChunks,
                  @Param("processedChunks") int processedChunks,
                  @Param("failedChunks") int failedChunks,
                  @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage);

    /**
     * 重试次数耗尽后把任务标记为最终失败。
     */
    @Update("""
            UPDATE kb_task
            SET status = #{failedStatus},
                total_chunks = #{totalChunks},
                processed_chunks = #{processedChunks},
                failed_chunks = #{failedChunks},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                finished_at = #{finishedAt},
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = #{processingStatus}
              AND retry_count >= max_retries
            """)
    int markFailed(@Param("taskId") Long taskId,
                   @Param("processingStatus") int processingStatus,
                   @Param("failedStatus") int failedStatus,
                   @Param("totalChunks") int totalChunks,
                   @Param("processedChunks") int processedChunks,
                   @Param("failedChunks") int failedChunks,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("finishedAt") LocalDateTime finishedAt);
}
