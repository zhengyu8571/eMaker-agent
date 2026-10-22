package com.tianji.aigc.enums;

import com.tianji.common.enums.BaseEnum;
import lombok.Getter;

/**
 * 聊天消息事件类型
 */
@Getter
public enum ChatEventTypeEnum implements BaseEnum {
    DATA(1001, "数据事件"),
    STOP(1002, "停止事件"),
    PARAM(1003, "参数事件"),
    // PLAN 链路的安全进度事件
    PROGRESS(1004, "进度事件");

    private final int value;
    private final String desc;

    ChatEventTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public String toString() {
        return this.name();
    }
}
