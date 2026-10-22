package com.tianji.aigc.tools;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.tools.result.CourseInfo;
import com.tianji.aigc.service.CourseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 课程工具
 */
@Component
@RequiredArgsConstructor
public class CourseTools {

    // 与MCP工具共用课程查询服务
    private final CourseQueryService courseQueryService;
    private static final String FIELD_NAME_FORMAT = "{}_{}";  // 提取格式字符串常量

    @Tool(description = Constant.Tools.QUERY_COURSE_BY_ID)
    public CourseInfo queryCourseById(@ToolParam(description = Constant.ToolParams.COURSE_ID) Long courseId, ToolContext toolContext) {
        return Optional.ofNullable(courseId)
                // 读取课程并转换为现有工具结果
                .map(this.courseQueryService::queryCourseById)
                .map(courseInfo -> {
                    // 将结果存储到容器中
                    var requestId = MapUtil.get(toolContext.getContext(), Constant.REQUEST_ID, String.class);
                    var field = StrUtil.format(FIELD_NAME_FORMAT, StrUtil.lowerFirst(CourseInfo.class.getSimpleName()), courseId);
                    ToolResultHolder.put(requestId, field, courseInfo);
                    return courseInfo;
                })
                .orElse(null);
    }

}
