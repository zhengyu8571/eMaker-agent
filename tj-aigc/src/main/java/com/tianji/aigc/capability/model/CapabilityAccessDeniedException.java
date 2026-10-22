package com.tianji.aigc.capability.model;

/**
 * 表示调用目标不在当前Skill的能力白名单中。
 */
public class CapabilityAccessDeniedException extends RuntimeException {

    public CapabilityAccessDeniedException(String message) {
        super(message);
    }
}
