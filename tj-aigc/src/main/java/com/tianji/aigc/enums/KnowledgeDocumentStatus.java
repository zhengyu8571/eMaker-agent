package com.tianji.aigc.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.tianji.common.enums.BaseEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 知识库文档当前版本的处理状态。
 */
@Getter
@RequiredArgsConstructor
public enum KnowledgeDocumentStatus implements BaseEnum {

    // 原文件已经保存
    UPLOADED(0, "已上传"),
    // 当前版本正在处理
    PROCESSING(1, "处理中"),
    // 当前版本已经建立索引
    INDEXED(2, "已建索引"),
    // 当前版本处理失败
    FAILED(3, "处理失败"),
    ;

    // 保存到数据库的状态值
    @EnumValue
    private final int value;
    // 展示状态含义
    private final String desc;
}
