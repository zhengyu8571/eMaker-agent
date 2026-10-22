package com.tianji.aigc.support.document;

import com.tianji.aigc.config.DocumentChunkingProperties;
import com.tianji.aigc.dto.KnowledgeDocumentChunkContext;
import com.tianji.common.exceptions.BadRequestException;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按标题、段落和 Token 上限切分知识库文档。
 */
@Component
public class KnowledgeDocumentChunker {

    // Chunk 元数据中的知识库标识 key
    public static final String METADATA_LIBRARY_KEY = "kb_key";

    // Chunk 元数据中的知识库 ID
    public static final String METADATA_LIBRARY_ID = "kb_id";

    // Chunk 元数据中的文档 ID
    public static final String METADATA_DOCUMENT_ID = "doc_id";

    // 精确保存长ID，避免向量库把JSON数字转为浮点数
    public static final String METADATA_DOCUMENT_ID_EXACT = "doc_id_exact";

    // 精确保存知识库ID，保留原数值字段兼容已有索引
    public static final String METADATA_LIBRARY_ID_EXACT = "kb_id_exact";

    // Chunk 元数据中的文档版本
    public static final String METADATA_DOCUMENT_VERSION = "document_version";

    // Chunk 元数据中的文档标题
    public static final String METADATA_DOCUMENT_TITLE = "document_title";

    // Chunk 所属章节标题
    public static final String METADATA_SECTION = "section";

    // Chunk 在整篇文档中的序号
    public static final String METADATA_CHUNK_ORDINAL = "chunk_ordinal";

    // Chunk 的 token 数量
    public static final String METADATA_TOKEN_COUNT = "token_count";

    // Chunk 内容哈希
    public static final String METADATA_CONTENT_HASH = "content_hash";

    // 段落之间使用两个换行符连接
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    // 匹配 Markdown 1~6 级标题，例如：## Spring AI
    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^(#{1,6})[ \\t]+(.+?)\\s*$");

    // 文档分块配置
    private final DocumentChunkingProperties properties;

    // 用于统计 token、截取指定 token 数量文本
    private final TokenEncoder tokenEncoder;

    // 用于生成稳定的 chunkId 和 contentHash
    private final StableChunkIdGenerator chunkIdGenerator;

    // 用于强制拆分单个超长段落
    private final TokenTextSplitter hardSplitter;

    public KnowledgeDocumentChunker(DocumentChunkingProperties properties,
                                    TokenEncoder tokenEncoder,
                                    StableChunkIdGenerator chunkIdGenerator) {

        // 启动时校验分块配置是否合法
        properties.validate();

        this.properties = properties;
        this.tokenEncoder = tokenEncoder;
        this.chunkIdGenerator = chunkIdGenerator;

        // 单个 unit 最大 token 数，需要为后续 overlap 预留空间
        var maxUnitTokens = Math.max(
                1,
                properties.getChunkSize() - properties.getOverlap() - 1
        );

        // 用于处理无法按正常段落逻辑容纳的超长文本
        this.hardSplitter = TokenTextSplitter.builder()
                .withChunkSize(maxUnitTokens)          // 单块最大 token 数
                .withMinChunkSizeChars(0)              // 不限制最小字符数
                .withMinChunkLengthToEmbed(0)          // 不限制最小可向量化长度
                .withMaxNumChunks(properties.getMaxChunks()) // 最大分块数
                .withKeepSeparator(true)               // 保留分隔符
                .build();
    }

    /**
     * 将整篇文档内容拆分成多个 Spring AI Document。
     */
    public List<Document> split(KnowledgeDocumentChunkContext context, String content) {

        // 校验知识库、文档 ID、版本等上下文信息
        validateContext(context);

        // 空文档直接返回空列表
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var chunks = new ArrayList<Document>();

        // 先按 Markdown 标题解析成 Section，再逐个 Section 进行分块
        for (var section : parseSections(content.strip())) {
            splitSection(context, section, chunks);
        }

        // 返回不可修改的 List
        return List.copyOf(chunks);
    }

    /**
     * 对单个章节进行分块。
     */
    private void splitSection(KnowledgeDocumentChunkContext context,
                              Section section,
                              List<Document> chunks) {

        // 当前正在累积的 chunk 内容
        var current = "";

        // 先把章节转换成适合拼接的 unit
        for (var unit : createUnits(section)) {

            // 当前 chunk 为空时，直接放入第一个 unit
            if (current.isEmpty()) {
                current = unit;
                continue;
            }

            // 尝试把下一个 unit 拼到当前 chunk 后面
            var candidate = current + PARAGRAPH_SEPARATOR + unit;

            // 拼接后没有超过 chunkSize，则继续累积
            if (this.tokenEncoder.count(candidate)
                    <= this.properties.getChunkSize()) {
                current = candidate;
                continue;
            }

            // 超过 chunkSize，先保存当前 chunk
            addChunk(context, section.title(), current, chunks);

            // 新 chunk 从上一块尾部 overlap + 当前 unit 开始
            current = joinWithOverlap(current, unit);
        }

        // 循环结束后，把最后剩余的内容加入 chunks
        if (!current.isBlank()) {
            addChunk(context, section.title(), current, chunks);
        }
    }

    /**
     * 将 Section 转换成一个个可参与分块的文本 unit。
     */
    private List<String> createUnits(Section section) {

        // 先复制当前章节的所有段落
        var sourceUnits = new ArrayList<>(section.paragraphs());

        // 当前章节存在 Markdown 标题行
        if (section.headingLine() != null) {

            // 如果章节没有正文，只保留标题本身
            if (sourceUnits.isEmpty()) {
                sourceUnits.add(section.headingLine());
            } else {

                // 有正文时，将标题拼到第一个段落前
                sourceUnits.set(
                        0,
                        section.headingLine()
                                + PARAGRAPH_SEPARATOR
                                + sourceUnits.get(0)
                );
            }
        }

        var units = new ArrayList<String>();

        // 为 overlap 预留 token 后，计算单个 unit 的最大 token 数
        var maxUnitTokens = Math.max(
                1,
                this.properties.getChunkSize()
                        - this.properties.getOverlap()
                        - 1
        );

        for (var sourceUnit : sourceUnits) {

            // 当前段落没有超过限制，直接作为一个 unit
            if (this.tokenEncoder.count(sourceUnit) <= maxUnitTokens) {
                units.add(sourceUnit);
                continue;
            }

            // 单个段落太长时，使用 Spring AI TokenTextSplitter 强制拆分
            var splitDocuments =
                    this.hardSplitter.apply(List.of(new Document(sourceUnit)));

            // 取出拆分后的文本，过滤空内容后加入 units
            splitDocuments.stream()
                    .map(Document::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .forEach(units::add);
        }

        return units;
    }

    /**
     * 给新 chunk 加入上一 chunk 尾部的 overlap 内容。
     */
    private String joinWithOverlap(String previousChunk, String nextUnit) {

        // 当前 nextUnit 本身占用多少 token
        var nextUnitTokens = this.tokenEncoder.count(nextUnit);

        // 当前 chunkSize 中还剩多少 token 可以留给 overlap
        var availableTokens =
                this.properties.getChunkSize() - nextUnitTokens;

        // overlap 不能超过配置值，也不能超过剩余可用空间
        var overlapTokens = Math.min(
                this.properties.getOverlap(),
                Math.max(0, availableTokens)
        );

        // 如果拼接后仍超限，就逐步减少 overlap
        while (overlapTokens > 0) {

            // 从上一 chunk 尾部截取指定数量 token
            var overlap =
                    this.tokenEncoder.tail(previousChunk, overlapTokens);

            // overlap 为空就只保留 nextUnit，否则两者拼接
            var candidate = overlap.isBlank()
                    ? nextUnit
                    : overlap + PARAGRAPH_SEPARATOR + nextUnit;

            // 没超过 chunkSize，则使用当前 overlap
            if (this.tokenEncoder.count(candidate)
                    <= this.properties.getChunkSize()) {
                return candidate;
            }

            // 超限则减少一个 overlap token 后重试
            overlapTokens--;
        }

        // 无法保留 overlap 时，只返回 nextUnit
        return nextUnit;
    }

    /**
     * 创建一个最终 Chunk，并补充对应元数据。
     */
    private void addChunk(KnowledgeDocumentChunkContext context,
                          String section,
                          String content,
                          List<Document> chunks) {

        // 限制整篇文档最大 chunk 数量
        if (chunks.size() >= this.properties.getMaxChunks()) {
            throw new BadRequestException("文档分块数量超过最大限制");
        }

        // 当前 chunk 的序号，从 0 开始
        var ordinal = chunks.size();

        // 根据文档 ID、版本、序号和内容生成稳定标识
        var identity = this.chunkIdGenerator.generate(
                context.getDocumentId(),
                context.getDocumentVersion(),
                ordinal,
                content
        );

        // LinkedHashMap 保留元数据的插入顺序
        var metadata = new LinkedHashMap<String, Object>();

        // 添加知识库相关元数据
        metadata.put(METADATA_LIBRARY_KEY, context.getLibraryKey());
        metadata.put(METADATA_LIBRARY_ID, context.getLibraryId());
        // 字符串副本不经过浮点转换
        metadata.put(METADATA_LIBRARY_ID_EXACT, context.getLibraryId().toString());

        // 添加文档相关元数据
        metadata.put(METADATA_DOCUMENT_ID, context.getDocumentId());
        // 原数值字段继续供已有NUMERIC索引使用
        metadata.put(METADATA_DOCUMENT_ID_EXACT, context.getDocumentId().toString());
        metadata.put(METADATA_DOCUMENT_VERSION, context.getDocumentVersion());

        // 文档标题非空时才添加
        if (context.getDocumentTitle() != null
                && !context.getDocumentTitle().isBlank()) {
            metadata.put(
                    METADATA_DOCUMENT_TITLE,
                    context.getDocumentTitle()
            );
        }

        // 章节标题非空时才添加
        if (section != null && !section.isBlank()) {
            metadata.put(METADATA_SECTION, section);
        }

        // 添加 chunk 序号
        metadata.put(METADATA_CHUNK_ORDINAL, ordinal);

        // 记录当前 chunk 的 token 数
        metadata.put(
                METADATA_TOKEN_COUNT,
                this.tokenEncoder.count(content)
        );

        // 记录当前 chunk 内容哈希
        metadata.put(
                METADATA_CONTENT_HASH,
                identity.contentHash()
        );

        // 创建 Spring AI Document，并加入 chunks
        chunks.add(
                Document.builder()
                        .id(identity.chunkId()) // 使用稳定 chunkId
                        .text(content)          // chunk 正文
                        .metadata(metadata)     // chunk 元数据
                        .build()
        );
    }

    /**
     * 按 Markdown 标题和空行，把全文解析成多个 Section。
     */
    private List<Section> parseSections(String content) {

        // 最终解析出的所有章节
        var sections = new ArrayList<Section>();

        // 当前章节完整 Markdown 标题行，例如 "## Redis"
        String headingLine = null;

        // 当前章节纯标题，例如 "Redis"
        String sectionTitle = null;

        // 当前章节已经完成的所有段落
        var paragraphs = new ArrayList<String>();

        // 正在累积的当前段落
        var paragraph = new StringBuilder();

        // 按换行拆分全文，-1 表示保留末尾空字符串
        for (var line : content.split("\\n", -1)) {

            // 判断当前行是不是 Markdown 标题
            var headingMatcher =
                    MARKDOWN_HEADING.matcher(line.strip());

            if (headingMatcher.matches()) {

                // 遇到新标题前，先保存正在累积的段落
                addParagraph(paragraph, paragraphs);

                // 当前章节已有标题或正文时，先保存旧章节
                if (headingLine != null || !paragraphs.isEmpty()) {
                    sections.add(
                            new Section(
                                    headingLine,
                                    sectionTitle,
                                    List.copyOf(paragraphs)
                            )
                    );
                }

                // 保存新的完整标题行
                headingLine = line.strip();

                // group(2) 是 Markdown # 后面的标题文字
                sectionTitle = headingMatcher.group(2).strip();

                // 开始收集新章节的段落
                paragraphs = new ArrayList<>();

                continue;
            }

            // 空行表示当前段落结束
            if (line.isBlank()) {
                addParagraph(paragraph, paragraphs);
                continue;
            }

            // 同一段落内多行文本之间保留一个换行
            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }

            // 去除行尾空白后加入当前段落
            paragraph.append(line.stripTrailing());
        }

        // 保存最后一个尚未结束的段落
        addParagraph(paragraph, paragraphs);

        // 保存最后一个章节
        if (headingLine != null || !paragraphs.isEmpty()) {
            sections.add(
                    new Section(
                            headingLine,
                            sectionTitle,
                            List.copyOf(paragraphs)
                    )
            );
        }

        return sections;
    }

    /**
     * 将当前 paragraph 保存到 paragraphs，并清空 StringBuilder。
     */
    private void addParagraph(StringBuilder paragraph,
                              List<String> paragraphs) {

        // 当前没有内容，不处理
        if (paragraph.isEmpty()) {
            return;
        }

        // 去除首尾空白后加入段落集合
        paragraphs.add(paragraph.toString().strip());

        // 清空 StringBuilder，继续收集下一个段落
        paragraph.setLength(0);
    }

    /**
     * 校验文档分块所需的上下文信息。
     */
    private void validateContext(KnowledgeDocumentChunkContext context) {

        // 上下文本身不能为空
        if (context == null) {
            throw new BadRequestException("文档分块上下文不能为空");
        }

        // 知识库业务标识不能为空
        if (context.getLibraryKey() == null
                || context.getLibraryKey().isBlank()) {
            throw new BadRequestException("知识库标识不能为空");
        }

        // 知识库 ID 和文档 ID 都不能为空
        if (context.getLibraryId() == null
                || context.getDocumentId() == null) {
            throw new BadRequestException("知识库ID和文档ID不能为空");
        }

        // 文档版本必须存在并且大于 0
        if (context.getDocumentVersion() == null
                || context.getDocumentVersion() <= 0) {
            throw new BadRequestException("文档版本必须大于 0");
        }
    }

    /**
     * 一个章节的数据结构：
     * headingLine：完整 Markdown 标题行，例如 "## Redis"
     * title：纯标题，例如 "Redis"
     * paragraphs：该章节下的所有段落
     */
    private record Section(
            String headingLine,
            String title,
            List<String> paragraphs
    ) {
    }
}
