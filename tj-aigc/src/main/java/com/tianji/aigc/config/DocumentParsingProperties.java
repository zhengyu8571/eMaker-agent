package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 知识库文档解析边界。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.knowledge.parsing")
public class DocumentParsingProperties {

    // 允许解析的最大文件大小
    private DataSize maxFileSize = DataSize.ofMegabytes(50);

    // 允许提取的最大正文字符数
    private int maxTextLength = 5 * 1024 * 1024;

    // 允许解析的文件扩展名
    private Set<String> allowedExtensions = new LinkedHashSet<>(
            List.of("pdf", "docx", "md", "markdown", "txt"));

    public boolean supports(String extension) {
        // 未提供扩展名时判定为不支持
        if (extension == null) {
            return false;
        }
        // 忽略大小写匹配允许的扩展名
        return this.allowedExtensions.stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(item -> item.equals(extension.toLowerCase(Locale.ROOT)));
    }
}
