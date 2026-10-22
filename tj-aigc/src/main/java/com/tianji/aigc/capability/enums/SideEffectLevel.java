package com.tianji.aigc.capability.enums;

/**
 * 描述能力调用对外部状态的影响程度。
 */
public enum SideEffectLevel {

    // 只读取数据，不修改外部状态
    READ_ONLY,

    // 会修改状态，但存在明确撤销或补偿方式
    WRITE_REVERSIBLE,

    // 会修改状态，且不能可靠自动撤销
    WRITE_IRREVERSIBLE
}
