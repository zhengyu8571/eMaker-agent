package com.tianji.aigc.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.tianji.common.enums.BaseEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 知识库异步任务状态机。
 *
 * <p>自动重试先进入 RETRY，再由消费者重新抢占为 PROCESSING；FAILED 只允许人工重新触发重试。</p>
 */
@Getter
@RequiredArgsConstructor
public enum KnowledgeTaskStatus implements BaseEnum {

    // 任务等待发送或处理
    PENDING(0, "待处理"),
    // 任务正在处理
    PROCESSING(1, "处理中"),
    // 任务等待再次处理
    RETRY(2, "等待重试"),
    // 任务已经完成索引
    INDEXED(3, "索引完成"),
    // 任务处理失败
    FAILED(4, "处理失败"),
    ;

    // 保存到数据库的状态值
    @EnumValue
    private final int value;
    // 展示状态含义
    private final String desc;

    public boolean canTransitionTo(KnowledgeTaskStatus target) {
        // 状态不能为空且不能原地转换
        if (target == null || target == this) {
            return false;
        }
        // 根据当前状态检查允许到达的目标状态
        return switch (this) {
            case PENDING -> target == PROCESSING || target == FAILED;
            case PROCESSING -> target == RETRY || target == INDEXED || target == FAILED;
            case RETRY -> target == PROCESSING || target == FAILED;
            case FAILED -> target == RETRY;
            case INDEXED -> false;
        };
    }

    public void checkTransitionTo(KnowledgeTaskStatus target) {
        // 非法状态转换直接停止处理
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("知识库任务状态不允许从 " + this + " 变更为 " + target);
        }
    }

    public static KnowledgeTaskStatus of(Integer value) {
        // 数据库状态为空时保持为空
        if (value == null) {
            return null;
        }
        // 根据数据库值查找对应枚举
        return Arrays.stream(values())
                .filter(status -> status.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的知识库任务状态：" + value));
    }
}
