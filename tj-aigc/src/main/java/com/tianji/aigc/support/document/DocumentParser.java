package com.tianji.aigc.support.document;

import com.tianji.aigc.config.DocumentParsingProperties;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 使用 Apache Tika 提取知识库文档正文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParser {

    // 清理 Tika 提取出的原始正文
    private final TextCleaner textCleaner;

    // 提供文件大小和文本长度等解析边界
    private final DocumentParsingProperties properties;

    /**
     * 解析调用方提供的流；流的关闭责任仍由调用方承担。
     */
    public String parseContent(InputStream inputStream, String fileName, long fileSize) {

        // 如果传进来的输入流为 null，说明根本没有文档内容，直接抛出参数异常
        if (inputStream == null) {
            throw new BadRequestException("文档内容不能为空");
        }

        // 在真正解析文档之前，先校验文件名、文件后缀、文件大小是否合法
        validate(fileName, fileSize);

        // 对文件名进行安全处理，比如把换行符替换掉，避免日志污染等问题
        var safeFileName = sanitizeFileName(fileName);

        // 记录开始解析文档的日志，打印文件名和文件大小
        log.info("开始解析知识库文档，fileName={}，fileSize={}", safeFileName, fileSize);

        try {
            // 创建 Apache Tika 的自动识别解析器
            // AutoDetectParser 会根据文档类型自动选择对应的解析器，
            // 比如 PDF、DOCX、TXT 等
            var parser = new AutoDetectParser();

            // 创建正文内容接收器
            // Tika 在解析文档时，会把解析出来的文本写进 handler
            // getMaxTextLength() 用来限制最大提取文本长度，防止超大文本占用过多内存
            var handler = new BodyContentHandler(this.properties.getMaxTextLength());

            // 创建元数据对象
            // Metadata 用于保存文件名、文件类型、作者等文档元信息
            var metadata = new Metadata();

            // 把安全处理后的文件名放到 Tika 的 Metadata 中
            // RESOURCE_NAME_KEY 表示“资源文件名”
            // Tika 可以利用文件名后缀辅助判断文档格式
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, safeFileName);

            // 创建解析上下文
            // ParseContext 可以理解成“解析过程中的配置容器”
            // 后面会把 Parser、PDF 配置等信息放进去
            var context = new ParseContext();

            // 把当前使用的 parser 注册到解析上下文中
            // 这样其他解析组件在需要 Parser 时，可以从 context 里获取
            context.set(Parser.class, parser);

            // 设置嵌入文档的处理方式
            // NoOpEmbeddedDocumentExtractor 表示这里不处理文档中嵌入的其他文件
            // 比如 Word 里嵌入的附件、PDF 里的嵌入文件等
            context.set(
                    EmbeddedDocumentExtractor.class,
                    new NoOpEmbeddedDocumentExtractor()
            );

            // 创建 PDF 专用的解析配置
            var pdfConfig = new PDFParserConfig();

            // 按照文字在 PDF 页面中的位置进行排序
            // 这样提取出来的文本顺序会更接近人眼看到的阅读顺序
            pdfConfig.setSortByPosition(true);

            // 不提取 PDF 中的图片
            // 当前知识库这里只关心文本内容
            pdfConfig.setImageStrategy(PDFParserConfig.IMAGE_STRATEGY.NONE);

            // 禁用 OCR
            // 即：对于扫描版 PDF 中“图片里的文字”，这里不会进行文字识别
            pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);

            // 把 PDF 配置放进解析上下文
            // 当 Tika 解析 PDF 时，会读取这里的 PDFParserConfig
            context.set(PDFParserConfig.class, pdfConfig);

            // 真正开始解析文档
            //
            // inputStream：文档原始输入流
            // handler：接收解析出来的正文
            // metadata：文档元数据
            // context：解析配置
            parser.parse(inputStream, handler, metadata, context);

            // handler.toString() 获取 Tika 提取出来的原始正文
            // 然后交给 textCleaner 做进一步文本清洗
            // 比如可能清理多余空格、换行、特殊字符等
            var cleaned = this.textCleaner.clean(handler.toString());

            // 记录解析成功日志
            // textLength 表示最终清洗后的文本长度
            log.info(
                    "知识库文档解析完成，fileName={}，textLength={}",
                    safeFileName,
                    cleaned.length()
            );

            // 返回最终清洗后的文档文本
            return cleaned;

            // 捕获文档解析过程中常见的三类异常：
            //
            // IOException：读取输入流时发生异常
            // TikaException：Apache Tika 自身解析失败
            // SAXException：Tika 内部 SAX 文本解析过程中发生异常
        } catch (IOException | TikaException | SAXException e) {

            // 打印警告日志
            // 这里只记录异常类型，不直接把完整异常内容拼进日志字符串
            log.warn(
                    "知识库文档解析失败，fileName={}，errorType={}",
                    safeFileName,
                    e.getClass().getSimpleName()
            );

            // 把底层的 Tika / IO 异常统一包装成项目自己的 CommonException
            // 上层业务就不需要关心具体是 IOException 还是 TikaException
            throw new CommonException("文档解析失败：" + safeFileName, e);
        }
    }

    private void validate(String fileName, long fileSize) {

        // 从文件名中提取文件扩展名
        // 比如：
        // "test.pdf" -> "pdf"
        // "hello.docx" -> "docx"
        var extension = extensionOf(fileName);

        // 判断当前文件扩展名是否在系统允许的扩展名集合中
        // properties.supports(extension)：
        //
        // 支持 -> true
        // 不支持 -> false
        if (!this.properties.supports(extension)) {

            // 如果不支持，则直接抛出请求参数异常
            // 同时把当前支持的所有文件类型告诉调用方
            throw new BadRequestException(
                    "不支持的文档类型：" + extension
                            + "，仅支持 " + this.properties.getAllowedExtensions()
            );
        }

        // 文件大小不能是负数
        // 正常情况下文件大小不应该小于 0，
        // 这里属于防御性校验
        if (fileSize < 0) {
            throw new BadRequestException("文档大小不能为负数");
        }

        // 判断当前文件是否超过配置中的最大允许大小
        //
        // getMaxFileSize() 返回 DataSize
        // toBytes() 把它转换成字节数再和 fileSize 比较
        if (fileSize > this.properties.getMaxFileSize().toBytes()) {

            // 文件过大，直接拒绝解析
            throw new BadRequestException(
                    "文档超过最大限制：" + this.properties.getMaxFileSize()
            );
        }
    }

    private String extensionOf(String fileName) {

        // 如果文件名为 null，
        // 或者文件名是空字符串 / 全是空格，
        // 那么无法判断文件后缀，返回 "unknown"
        if (fileName == null || fileName.isBlank()) {
            return "unknown";
        }

        // 去掉文件名前后的空格
        //
        // 比如：
        // "  test.pdf  "
        // 变成：
        // "test.pdf"
        var normalized = fileName.trim();

        // 查找最后一个 '.' 出现的位置
        //
        // 为什么是 lastIndexOf，而不是 indexOf？
        //
        // 比如：
        // "my.test.file.pdf"
        //
        // 我们真正要的是最后面的 pdf，
        // 所以要找最后一个点
        var dotIndex = normalized.lastIndexOf('.');

        // 两种情况都认为没有合法扩展名
        //
        // dotIndex < 0：
        // 文件名里根本没有 "."
        // 例如：
        // "README"
        //
        // dotIndex == normalized.length() - 1：
        // "." 恰好是最后一个字符
        // 例如：
        // "test."
        // 点后面没有任何扩展名
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return "unknown";
        }

        // 从最后一个 "." 的下一个位置开始截取字符串
        //
        // 比如：
        // "hello.pdf"
        //
        // dotIndex 指向 "."
        // dotIndex + 1 指向 "p"
        //
        // substring(...) 最终得到：
        // "pdf"
        //
        // 再统一转成小写：
        // "PDF" -> "pdf"
        // "DocX" -> "docx"
        return normalized
                .substring(dotIndex + 1)
                .toLowerCase(Locale.ROOT);
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replace('\r', '_').replace('\n', '_');
    }
}
