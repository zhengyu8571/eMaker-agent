package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.knowledge.chunking")
public class DocumentChunkingProperties {

    // 单个分块允许的最大 Token 数
    private int chunkSize = 800;

    // 相邻分块保留的重叠 Token 数
    private int overlap = 80;

    // 单个文档允许生成的最大分块数
    private int maxChunks = 10000;

    public void validate() {
        // 分块大小必须是正整数
        if (this.chunkSize <= 0) {
            throw new IllegalStateException("chunk-size 必须大于 0");
        }
        // 重叠量不能为负数或达到分块大小
        if (this.overlap < 0 || this.overlap >= this.chunkSize) {
            throw new IllegalStateException("overlap 必须大于等于 0 且小于 chunk-size");
        }
        // 最大分块数必须是正整数
        if (this.maxChunks <= 0) {
            throw new IllegalStateException("max-chunks 必须大于 0");
        }
    }
}
