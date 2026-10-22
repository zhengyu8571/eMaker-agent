package com.tianji.aigc.service;

import com.tianji.aigc.vo.KnowledgeTaskVO;

/**
 * 查询知识库异步任务。
 */
public interface KnowledgeTaskService {

    /**
     * 查询指定任务的状态和进度。
     */
    KnowledgeTaskVO getById(Long taskId);
}
