package com.tianji.aigc.support.document;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.xml.sax.ContentHandler;

import java.io.InputStream;

/**
 * 禁止 Tika 递归解析文档内嵌的图片和附件。
 */
final class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        // 拒绝解析文档中的嵌入内容
        return false;
    }

    @Override
    public void parseEmbedded(InputStream stream,
                              ContentHandler handler,
                              Metadata metadata,
                              boolean outputHtml) {
        // shouldParseEmbedded() 始终返回 false，不会进入这里。
    }
}
