package com.tianji.aigc.skill;

import java.util.Set;

/**
 * 保存知识库稳定键的启动校验结果。
 */
public record KnowledgeLibraryKeyValidation(
        Set<String> missingKeys,
        Set<String> duplicateKeys
) {

    public KnowledgeLibraryKeyValidation {
        missingKeys = missingKeys == null ? Set.of() : Set.copyOf(missingKeys);
        duplicateKeys = duplicateKeys == null ? Set.of() : Set.copyOf(duplicateKeys);
    }

    public static KnowledgeLibraryKeyValidation valid() {
        return new KnowledgeLibraryKeyValidation(Set.of(), Set.of());
    }

    public boolean validResult() {
        return missingKeys.isEmpty() && duplicateKeys.isEmpty();
    }
}
