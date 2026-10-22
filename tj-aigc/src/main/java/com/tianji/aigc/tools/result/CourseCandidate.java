package com.tianji.aigc.tools.result;

import java.util.List;

/** 保存课程搜索返回的首个候选 ID 和有限候选列表。 */
public record CourseCandidate(Long courseId, List<Long> courseIds, String keyword) {

    public CourseCandidate {
        // 固定课程 ID 列表防止下游修改
        courseIds = courseIds == null ? List.of() : List.copyOf(courseIds);
    }
}
