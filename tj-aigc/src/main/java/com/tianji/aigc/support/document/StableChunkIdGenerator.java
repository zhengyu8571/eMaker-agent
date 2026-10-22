package com.tianji.aigc.support.document;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class StableChunkIdGenerator {

    public ChunkIdentity generate(Long documentId,
                                  Integer documentVersion,
                                  int chunkOrdinal,
                                  String content) {
        // 根据分块正文生成内容哈希
        var contentHash = sha256(content);
        // 拼接影响分块身份的稳定字段
        var source = documentId + ":" + documentVersion + ":" + chunkOrdinal + ":" + contentHash;
        // 同时返回分块ID和内容哈希
        return new ChunkIdentity(sha256(source), contentHash);
    }

    private String sha256(String source) {
        try {
            // 创建 SHA-256 摘要计算器
            var digest = MessageDigest.getInstance("SHA-256");
            // 计算字符串摘要并转换成十六进制
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 当前运行环境缺少 SHA-256 时停止处理
            throw new IllegalStateException("当前环境不支持 SHA-256", e);
        }
    }

    // 保存稳定分块ID及其内容哈希
    public record ChunkIdentity(String chunkId, String contentHash) {
    }
}
