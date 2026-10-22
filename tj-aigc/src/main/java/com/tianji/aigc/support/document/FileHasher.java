package com.tianji.aigc.support.document;

import com.tianji.common.exceptions.CommonException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 计算文件内容哈希。
 */
@Component
public class FileHasher {

    // 每次读取文件的字节数
    private static final int BUFFER_SIZE = 8192;

    public String sha256(InputStream inputStream) {
        try {
            // 创建 SHA-256 摘要计算器
            var digest = MessageDigest.getInstance("SHA-256");
            // 创建分批读取文件的缓冲区
            var buffer = new byte[BUFFER_SIZE];
            int length;
            // 分批读取文件并更新摘要
            while ((length = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            // 将摘要转换成十六进制字符串
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            // 将读取和算法异常统一转换成项目异常
            throw new CommonException("计算文件哈希失败", e);
        }
    }
}
