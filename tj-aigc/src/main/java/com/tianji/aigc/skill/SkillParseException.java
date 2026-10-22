package com.tianji.aigc.skill;

/**
 * 表示单份Skill配置无法被安全解析。
 */
public class SkillParseException extends RuntimeException {

    public SkillParseException(String message) {
        super(message);
    }

    public SkillParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
