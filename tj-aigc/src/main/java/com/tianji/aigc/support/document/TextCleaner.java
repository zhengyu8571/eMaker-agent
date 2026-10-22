package com.tianji.aigc.support.document;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 在分块和向量化前清理文档正文，同时尽量保留段落结构。
 */
@Component
public class TextCleaner {

    // 匹配指定范围内的控制字符
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    // 匹配零宽字符、Word Joiner、BOM 等不可见字符
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");

    // 匹配单独占一行的 image数字.图片后缀，例如 image1.png
    private static final Pattern IMAGE_FILENAME_LINE =
            Pattern.compile("(?im)^\\s*image\\d+\\.(png|jpe?g|gif|bmp|webp)\\s*$");

    // 匹配 Markdown 图片语法，例如 ![xxx](xxx.png)
    private static final Pattern MARKDOWN_IMAGE =
            Pattern.compile("!\\[[^]]*]\\([^)]*\\)");

    // 匹配以 http/https 开头的图片 URL
    private static final Pattern IMAGE_URL =
            Pattern.compile("https?://\\S+?\\.(png|jpe?g|gif|bmp|webp)(\\?\\S*)?",
                    Pattern.CASE_INSENSITIVE);

    // 匹配 data:image/...;base64,... 格式的图片数据
    private static final Pattern DATA_IMAGE =
            Pattern.compile("data:image/[^;\\s]+;base64,[A-Za-z0-9+/=]+",
                    Pattern.CASE_INSENSITIVE);

    // 匹配 file: 或 file:// 开头的文件 URL
    private static final Pattern FILE_URL =
            Pattern.compile("file:(//)?\\S+", Pattern.CASE_INSENSITIVE);

    // 匹配由至少 3 个 -、_、*、= 组成的独立分隔行
    private static final Pattern SEPARATOR_LINE =
            Pattern.compile("(?m)^\\s*[-_*=]{3,}\\s*$");

    // 匹配每一行末尾的空格或 Tab
    private static final Pattern TRAILING_SPACES =
            Pattern.compile("(?m)[ \\t]+$");

    // 匹配连续 3 个及以上的换行符
    private static final Pattern EXCESSIVE_BLANK_LINES =
            Pattern.compile("\\n{3,}");

    public String clean(String text) {

        // text 为 null、空字符串或全为空白时，直接返回空字符串
        if (text == null || text.isBlank()) {
            return "";
        }

        // 将 \r\n 和 \r 统一替换为 \n
        var cleaned = text.replace("\r\n", "\n").replace('\r', '\n');

        // 删除匹配到的控制字符
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("");

        // 删除匹配到的不可见字符
        cleaned = INVISIBLE_CHARS.matcher(cleaned).replaceAll("");

        // 删除匹配到的 Markdown 图片语法
        cleaned = MARKDOWN_IMAGE.matcher(cleaned).replaceAll("");

        // 删除匹配到的图片文件名行
        cleaned = IMAGE_FILENAME_LINE.matcher(cleaned).replaceAll("");

        // 删除匹配到的图片 URL
        cleaned = IMAGE_URL.matcher(cleaned).replaceAll("");

        // 删除匹配到的 Base64 图片数据
        cleaned = DATA_IMAGE.matcher(cleaned).replaceAll("");

        // 删除匹配到的 file URL
        cleaned = FILE_URL.matcher(cleaned).replaceAll("");

        // 删除匹配到的分隔行
        cleaned = SEPARATOR_LINE.matcher(cleaned).replaceAll("");

        // 删除每一行末尾的空格和 Tab
        cleaned = TRAILING_SPACES.matcher(cleaned).replaceAll("");

        // 将连续 3 个及以上换行替换为 2 个换行
        cleaned = EXCESSIVE_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");

        // 删除整个字符串首尾的空白字符
        return cleaned.strip();
    }
}
