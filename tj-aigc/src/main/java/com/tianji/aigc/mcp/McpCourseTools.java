package com.tianji.aigc.mcp;

import com.tianji.aigc.service.CourseQueryService;
import com.tianji.aigc.tools.result.CourseCandidate;
import com.tianji.aigc.tools.result.CourseInfo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 仅发布无聊天状态的只读课程工具。 */
public class McpCourseTools {
    // 本地工具和MCP共用的查询接口
    private final CourseQueryService courseQueryService;

    // 注入课程查询服务
    public McpCourseTools(CourseQueryService courseQueryService) {
        // 保存课程查询服务
        this.courseQueryService = courseQueryService;
    }

    // 声明只读课程搜索工具
    @Tool(description = "根据简短主题搜索真实课程候选，返回首个courseId和最多五个courseIds；不创建或修改课程")
    public CourseCandidate searchCourseCandidate(@ToolParam(description = "简短课程主题，例如Java") String keyword) {
        // 返回结构化课程候选
        return this.courseQueryService.searchCourseCandidate(keyword);
    }

    // 声明只读课程详情工具
    @Tool(description = "根据已查询到的课程ID读取课程名称、人民币价格、有效期、适用人群和介绍")
    public CourseInfo queryCourseById(@ToolParam(description = "真实课程ID，必须大于0") Long courseId) {
        // 返回现有课程详情对象
        return this.courseQueryService.queryCourseById(courseId);
    }
}
