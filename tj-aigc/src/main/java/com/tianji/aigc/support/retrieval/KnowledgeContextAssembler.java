package com.tianji.aigc.support.retrieval;

import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.KnowledgeRetrievalProperties;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.dto.SourceRef;
import com.tianji.aigc.support.document.TokenEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按Token预算筛选知识Chunk，并生成与最终Chunk一一对应的来源。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeContextAssembler {

    // 使用与文档切块相同的Token计算方式
    private final TokenEncoder tokenEncoder;

    // 读取上下文预算和最低精排分数
    private final KnowledgeRetrievalProperties retrievalProperties;

    public Assembly assemble(List<RetrievedChunk> rankedChunks) {
        if (rankedChunks == null || rankedChunks.isEmpty()) {
            return Assembly.empty();
        }

        var properties = this.retrievalProperties.getContext();
        var remainingTokens = Math.max(1, properties.getMaxTokens());
        var selectedChunks = new ArrayList<RetrievedChunk>();
        var sources = new ArrayList<SourceRef>();

        for (var chunk : rankedChunks) {
            if (!isValid(chunk, properties.getMinRerankScore())) {
                continue;
            }

            var citationId = "S" + (selectedChunks.size() + 1);
            var source = toSource(citationId, chunk);
            var header = renderHeader(source);
            var separator = selectedChunks.isEmpty() ? "" : "\n\n";
            var separatorTokens = this.tokenEncoder.count(separator);
            var headerTokens = this.tokenEncoder.count(header);
            var contentTokens = this.tokenEncoder.count(chunk.content());
            var blockTokens = separatorTokens + headerTokens + contentTokens;

            if (blockTokens <= remainingTokens) {
                selectedChunks.add(chunk);
                sources.add(source);
                remainingTokens -= blockTokens;
                continue;
            }

            // 最后一个Chunk只保留预算内的开头正文
            var availableContentTokens = remainingTokens - separatorTokens - headerTokens;
            var truncatedContent = this.tokenEncoder.head(chunk.content(), availableContentTokens);
            if (StrUtil.isNotBlank(truncatedContent)) {
                selectedChunks.add(withContent(chunk, truncatedContent));
                sources.add(source);
            }
            break;
        }

        return new Assembly(selectedChunks, sources);
    }

    public String render(List<RetrievedChunk> chunks, List<SourceRef> sources) {
        if (chunks == null || sources == null || chunks.isEmpty() || sources.isEmpty()) {
            return "";
        }

        var blockCount = Math.min(chunks.size(), sources.size());
        var result = new StringBuilder();
        for (var index = 0; index < blockCount; index++) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(renderHeader(sources.get(index)))
                    .append(chunks.get(index).content());
        }
        return result.toString();
    }

    private boolean isValid(RetrievedChunk chunk, double minRerankScore) {
        if (chunk == null || StrUtil.isBlank(chunk.chunkId()) || StrUtil.isBlank(chunk.content())) {
            return false;
        }
        // 只有Rerank成功返回分数时才应用精排阈值
        return chunk.rerankScore() == null
                || chunk.rerankScore() >= Math.max(0d, minRerankScore);
    }

    private SourceRef toSource(String citationId, RetrievedChunk chunk) {
        return new SourceRef(
                citationId,
                chunk.chunkId(),
                chunk.libraryKey(),
                chunk.documentId(),
                chunk.documentVersion(),
                chunk.documentTitle(),
                chunk.section());
    }

    private String renderHeader(SourceRef source) {
        return "[%s] 文档：%s；章节：%s\n内容：".formatted(
                source.citationId(),
                display(source.documentTitle()),
                display(source.section()));
    }

    private String display(String value) {
        return StrUtil.isBlank(value) ? "未标注" : value.strip();
    }

    private RetrievedChunk withContent(RetrievedChunk chunk, String content) {
        return new RetrievedChunk(
                chunk.chunkId(),
                content,
                chunk.libraryKey(),
                chunk.libraryId(),
                chunk.documentId(),
                chunk.documentVersion(),
                chunk.documentTitle(),
                chunk.section(),
                chunk.chunkOrdinal(),
                this.tokenEncoder.count(content),
                chunk.retrievalScores(),
                chunk.fusionScore(),
                chunk.rerankScore());
    }

    /**
     * 保存裁剪后的Chunk和对应来源。
     */
    public record Assembly(List<RetrievedChunk> chunks, List<SourceRef> sources) {

        public Assembly {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public static Assembly empty() {
            return new Assembly(List.of(), List.of());
        }
    }
}
