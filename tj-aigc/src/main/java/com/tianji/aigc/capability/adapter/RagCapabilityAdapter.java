package com.tianji.aigc.capability.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.Capability;
import com.tianji.aigc.capability.CapabilityKeys;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.capability.model.CapabilityCallContext;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.aigc.dto.RetrievalRequest;
import com.tianji.aigc.service.HybridRagService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 将阶段三混合检索入口包装成只读RAG能力。
 */
@Component
public class RagCapabilityAdapter implements Capability {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string"},"conversationId":{"type":"string"},"libraryKeys":{"type":"array","items":{"type":"string"}}},"required":["query","libraryKeys"]}
            """;
    private static final String OUTPUT_SCHEMA = """
            {"type":"object","properties":{"normalizedQuery":{"type":"string"},"chunks":{"type":"array"},"sources":{"type":"array"}},"required":["normalizedQuery","chunks","sources"]}
            """;

    private final HybridRagService hybridRagService;
    private final ObjectMapper objectMapper;
    private final CapabilityDescriptor descriptor = new CapabilityDescriptor(
            CapabilityKeys.KNOWLEDGE_RAG,
            CapabilityKind.RAG,
            "在指定知识库范围执行Query Rewrite、双路召回、RRF融合、精排和上下文裁剪",
            INPUT_SCHEMA,
            OUTPUT_SCHEMA,
            SideEffectLevel.READ_ONLY,
            Duration.ofSeconds(15),
            true,
            true);

    public RagCapabilityAdapter(HybridRagService hybridRagService,
                                ObjectMapper objectMapper) {
        this.hybridRagService = hybridRagService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return this.descriptor;
    }

    @Override
    public CapabilityResult execute(Map<String, Object> input,
                                    CapabilityCallContext context) {
        var query = requiredText(input, "query");
        var conversationId = optionalText(input, "conversationId");
        if (conversationId == null) {
            // 与ChatService保持同一规则，但不依赖可能丢失的ThreadLocal
            conversationId = context.userId() + "_" + context.sessionId();
        }
        var libraryKeys = readLibraryKeys(input);
        if (libraryKeys.isEmpty()) {
            libraryKeys = context.skill().knowledgeBaseKeys();
        }

        var result = this.hybridRagService.retrieve(
                new RetrievalRequest(query, conversationId, libraryKeys));
        return new CapabilityResult(
                result,
                result.sources(),
                Map.of("normalizedQuery", result.normalizedQuery(), "trace", result.trace()));
    }

    private Set<String> readLibraryKeys(Map<String, Object> input) {
        if (input == null || input.get("libraryKeys") == null) {
            return Set.of();
        }
        return this.objectMapper.convertValue(
                input.get("libraryKeys"), new TypeReference<Set<String>>() { });
    }

    private String requiredText(Map<String, Object> input, String field) {
        var value = optionalText(input, field);
        if (value == null) {
            throw new IllegalArgumentException("RAG能力参数" + field + "不能为空");
        }
        return value;
    }

    private String optionalText(Map<String, Object> input, String field) {
        var value = input == null ? null : input.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.strip();
    }
}
