package com.tianji.aigc.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.tianji.common.enums.BaseEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 知识库异步任务类型。
 */
@Getter
@RequiredArgsConstructor
public enum KnowledgeTaskType implements BaseEnum {

    // 解析文档并建立检索索引
    DOCUMENT_INDEX(1, "文档建索引"),
    ;

    // 保存到数据库的类型值
    @EnumValue
    private final int value;
    // 展示任务类型含义
    private final String desc;
}
