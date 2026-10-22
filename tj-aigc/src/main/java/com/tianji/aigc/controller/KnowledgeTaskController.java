package com.tianji.aigc.controller;

import com.tianji.aigc.service.KnowledgeTaskService;
import com.tianji.aigc.vo.KnowledgeTaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供知识库异步任务查询接口。
 */
@RestController
@RequestMapping("/knowledge-tasks")
@RequiredArgsConstructor
public class KnowledgeTaskController {

    // 查询任务状态和分块进度
    private final KnowledgeTaskService taskService;

    @GetMapping("/{taskId}")
    public KnowledgeTaskVO getById(@PathVariable("taskId") Long taskId) {
        // 将任务ID交给业务层查询
        return this.taskService.getById(taskId);
    }
}
