package com.tianji.aigc.service.impl;

import com.tianji.aigc.service.CourseQueryService;
import com.tianji.aigc.tools.result.CourseCandidate;
import com.tianji.aigc.tools.result.CourseInfo;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import org.springframework.stereotype.Service;

import java.util.List;

/** 复用Feign查询课程，查询过程不依赖聊天临时状态。 */
@Service
public class CourseQueryServiceImpl implements CourseQueryService {
    // 查询课程基础信息
    private final CourseClient courseClient;
    // 查询课程候选ID
    private final SearchClient searchClient;

    // 注入已有微服务客户端
    public CourseQueryServiceImpl(CourseClient courseClient, SearchClient searchClient) {
        // 保存课程客户端
        this.courseClient = courseClient;
        // 保存搜索客户端
        this.searchClient = searchClient;
    }

    @Override
    public CourseInfo queryCourseById(Long courseId) {
        // 不允许无效课程ID进入远端查询
        if (courseId == null || courseId <= 0) {
            // 返回明确的参数错误
            throw new IllegalArgumentException("课程ID必须大于0");
        }
        // 读取课程并复用已有价格转换
        return CourseInfo.of(this.courseClient.baseInfo(courseId, true));
    }

    @Override
    public CourseCandidate searchCourseCandidate(String keyword) {
        // 防止空关键词产生无边界搜索
        if (keyword == null || keyword.isBlank()) {
            // 返回明确的参数错误
            throw new IllegalArgumentException("课程搜索关键词不能为空");
        }
        // 复用现有搜索服务
        var found = this.searchClient.queryCoursesIdByName(keyword.strip());
        // 过滤无效ID、去重并限制为五个候选
        List<Long> ids = found == null ? List.of() : found.stream().filter(id -> id != null && id > 0).distinct().limit(5).toList();
        // 首个候选允许后续步骤绑定
        var first = ids.isEmpty() ? null : ids.get(0);
        // 保留完整候选列表和实际关键词
        return new CourseCandidate(first, ids, keyword.strip());
    }
}
