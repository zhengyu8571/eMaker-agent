package com.tianji.aigc.support.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

/**
 * 按 CL100K_BASE 编码计算和截取 Token。
 */
@Component
public class TokenEncoder {

    // 延迟加载 CL100K_BASE 编码器
    private final Encoding encoding = Encodings.newLazyEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        // 空文本不包含 Token
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 返回文本编码后的 Token 数量
        return this.encoding.countTokens(text);
    }

    public String tail(String text, int tokenCount) {
        // 输入无效时不截取文本
        if (text == null || text.isEmpty() || tokenCount <= 0) {
            return "";
        }
        // 将文本编码成 Token 序列
        var tokens = this.encoding.encode(text);
        // 计算末尾 Token 的起始位置
        var start = Math.max(0, tokens.size() - tokenCount);
        // 创建保存末尾 Token 的序列
        var tailTokens = new IntArrayList(tokens.size() - start);
        // 复制末尾指定数量的 Token
        for (var index = start; index < tokens.size(); index++) {
            tailTokens.add(tokens.get(index));
        }
        // 将末尾 Token 解码并清理首尾空白
        return this.encoding.decode(tailTokens).strip();
    }

    public String head(String text, int tokenCount) {
        // 输入无效时不截取文本
        if (text == null || text.isEmpty() || tokenCount <= 0) {
            return "";
        }
        // 只解码开头指定数量的Token
        var tokens = this.encoding.encode(text);
        var size = Math.min(tokens.size(), tokenCount);
        var headTokens = new IntArrayList(size);
        for (var index = 0; index < size; index++) {
            headTokens.add(tokens.get(index));
        }
        return this.encoding.decode(headTokens).strip();
    }
}
