package com.tianji.aigc.skill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.aigc.entity.KnowledgeLibrary;
import com.tianji.aigc.mapper.KnowledgeLibraryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通过知识库表校验Skill使用的跨环境稳定键。
 */
@Component
@RequiredArgsConstructor
public class DatabaseKnowledgeLibraryKeyValidator implements KnowledgeLibraryKeyValidator {

    private final KnowledgeLibraryMapper libraryMapper;

    @Override
    public KnowledgeLibraryKeyValidation validate(Set<String> libraryKeys) {
        if (libraryKeys == null || libraryKeys.isEmpty()) {
            return KnowledgeLibraryKeyValidation.valid();
        }
        var libraries = this.libraryMapper.selectList(
                Wrappers.<KnowledgeLibrary>lambdaQuery()
                        .in(KnowledgeLibrary::getLibraryKey, libraryKeys)
                        .select(KnowledgeLibrary::getLibraryKey));
        Map<String, Long> counts = libraries.stream()
                .map(KnowledgeLibrary::getLibraryKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.groupingBy(
                        String::strip, Collectors.counting()));

        var missingKeys = new LinkedHashSet<>(libraryKeys);
        missingKeys.removeAll(counts.keySet());
        var duplicateKeys = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new KnowledgeLibraryKeyValidation(missingKeys, duplicateKeys);
    }
}
