package com.tianji.aigc.skill;

import java.util.Set;

/**
 * 校验Skill声明的知识库稳定键是否可用。
 */
public interface KnowledgeLibraryKeyValidator {

    KnowledgeLibraryKeyValidation validate(Set<String> libraryKeys);
}
