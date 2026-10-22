package com.tianji.aigc.support.retrieval;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.aigc.config.KnowledgeRetrievalProperties;
import com.tianji.aigc.entity.KnowledgeLibrary;
import com.tianji.aigc.mapper.KnowledgeLibraryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 确定KnowledgeAgent本次允许检索的知识库范围。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeLibraryScopeResolver {

    // 查询数据库中已启用的知识库
    private final KnowledgeLibraryMapper libraryMapper;

    // 允许配置固定的默认知识库范围
    private final KnowledgeRetrievalProperties retrievalProperties;

    public Set<String> resolve() {
        var configuredKeys = normalize(
                this.retrievalProperties.getAgentScope().getLibraryKeys());
        if (!configuredKeys.isEmpty()) {
            return configuredKeys;
        }

        return findEnabledKeys(Set.of());
    }

    public Set<String> resolve(Set<String> requestedKeys) {
        var normalizedKeys = normalize(requestedKeys);
        if (normalizedKeys.isEmpty()) {
            return resolve();
        }
        // Skill指定范围时只返回其中仍处于启用状态的知识库
        return findEnabledKeys(normalizedKeys);
    }

    private Set<String> findEnabledKeys(Set<String> requestedKeys) {
        var query = Wrappers.lambdaQuery(KnowledgeLibrary.class)
                .select(KnowledgeLibrary::getLibraryKey)
                .eq(KnowledgeLibrary::getStatus, 1);
        if (!requestedKeys.isEmpty()) {
            query.in(KnowledgeLibrary::getLibraryKey, requestedKeys);
        }
        var libraries = this.libraryMapper.selectList(query);
        var enabledKeys = new LinkedHashSet<String>();
        for (var library : libraries) {
            if (library != null && StrUtil.isNotBlank(library.getLibraryKey())) {
                var libraryKey = library.getLibraryKey().strip();
                // 即使数据访问层异常返回了额外记录，也不允许越过Skill范围
                if (requestedKeys.isEmpty() || requestedKeys.contains(libraryKey)) {
                    enabledKeys.add(libraryKey);
                }
            }
        }
        return Set.copyOf(enabledKeys);
    }

    private Set<String> normalize(Set<String> libraryKeys) {
        if (libraryKeys == null || libraryKeys.isEmpty()) {
            return Set.of();
        }
        var normalizedKeys = new LinkedHashSet<String>();
        for (var libraryKey : libraryKeys) {
            if (StrUtil.isNotBlank(libraryKey)) {
                normalizedKeys.add(libraryKey.strip());
            }
        }
        return Set.copyOf(normalizedKeys);
    }
}
