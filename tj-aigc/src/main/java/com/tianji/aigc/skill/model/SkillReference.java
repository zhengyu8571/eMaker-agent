package com.tianji.aigc.skill.model;

/**
 * 描述Skill需要加载的一份短引用文件。
 */
public record SkillReference(
        String key,
        String ref,
        boolean shared
) {
}
