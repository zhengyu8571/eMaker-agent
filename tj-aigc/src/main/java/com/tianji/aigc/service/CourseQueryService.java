package com.tianji.aigc.service;

import com.tianji.aigc.tools.result.CourseCandidate;
import com.tianji.aigc.tools.result.CourseInfo;

/** 本地工具和MCP共用的只读课程查询。 */
public interface CourseQueryService {
    // 按课程ID读取详情
    CourseInfo queryCourseById(Long courseId);
    // 按关键词读取有限课程候选
    CourseCandidate searchCourseCandidate(String keyword);
}
