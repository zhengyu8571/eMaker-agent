package com.tianji.aigc.tools;

import com.tianji.aigc.tools.result.CourseCandidate;
import com.tianji.aigc.service.CourseQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 只读查询课程候选 ID，不参与原有业务 Agent 的默认工具列表。 */
@Component
public class CourseSearchTools {

    // 与MCP工具共用课程搜索服务
    private final CourseQueryService courseQueryService;

    public CourseSearchTools(CourseQueryService courseQueryService) {
        // 保存课程查询服务
        this.courseQueryService = courseQueryService;
    }

    @Tool(description = "根据简短课程主题查询候选课程ID，例如Java；只返回已有课程，不创建或修改课程")
    public CourseCandidate searchCourseCandidate(@ToolParam(description = "简短课程主题关键词，例如Java") String keyword) {
        // 校验和查询统一交给共用服务
        return this.courseQueryService.searchCourseCandidate(keyword);
    }
}
