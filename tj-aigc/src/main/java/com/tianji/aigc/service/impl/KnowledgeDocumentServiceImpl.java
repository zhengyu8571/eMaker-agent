package com.tianji.aigc.service.impl;

import com.tianji.aigc.config.DocumentParsingProperties;
import com.tianji.aigc.dto.KnowledgeDocumentCreateDTO;
import com.tianji.aigc.service.FileStorage;
import com.tianji.aigc.service.KnowledgeDocumentService;
import com.tianji.aigc.support.document.FileHasher;
import com.tianji.aigc.vo.KnowledgeDocumentUploadVO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final String STORAGE_PREFIX = "knowledge-base";

    // 按日期组织 OSS 文件路径
    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // 上传和删除 OSS 文件
    private final FileStorage fileStorage;

    // 计算上传文件的内容哈希
    private final FileHasher fileHasher;

    // 提供文件大小和扩展名限制
    private final DocumentParsingProperties parsingProperties;

    // 在事务中保存文档和任务记录
    private final KnowledgeDocumentPersistenceService persistenceService;

    @Override
    public KnowledgeDocumentUploadVO upload(Long libraryId, String documentName, MultipartFile file) {
        // 校验文件并提取名称和扩展名
        var fileMetadata = validateAndBuildMetadata(libraryId, documentName, file);

        // 上传前检查知识库状态
        this.persistenceService.checkLibraryAvailable(libraryId);

        // 生成本次上传使用的 OSS 存储地址
        var storageKey = createStorageKey(libraryId, fileMetadata.extension());

        // 计算原文件的 SHA-256 哈希
        var fileHash = calculateHash(file);

        try {
            // 重新打开文件流并上传原文件
            try (var inputStream = file.getInputStream()) {
                this.fileStorage.uploadFile(storageKey, inputStream, file.getSize());
            }

            // 构建保存文档和任务需要的数据
            var dto = KnowledgeDocumentCreateDTO.builder()
                    .libraryId(libraryId)
                    .documentName(fileMetadata.documentName())
                    .originalFileName(fileMetadata.originalFileName())
                    .fileType(normalizeFileType(fileMetadata.extension()))
                    .contentType(file.getContentType())
                    .storageKey(storageKey)
                    .fileSize(file.getSize())
                    .fileHash(fileHash)
                    .build();
            // 在同一事务中保存文档和待处理任务
            return this.persistenceService.createDocumentAndTask(dto);
        } catch (IOException e) {
            var exception = new CommonException("读取上传文件失败", e);
            // 上传流程失败时清理可能已写入的文件
            deleteUploadedFile(storageKey, exception);
            throw exception;
        } catch (RuntimeException e) {
            // 上传流程失败时清理可能已写入的文件
            deleteUploadedFile(storageKey, e);
            throw e;
        }
    }

    private UploadFileMetadata validateAndBuildMetadata(Long libraryId,
                                                        String documentName,
                                                        MultipartFile file) {
        if (libraryId == null) {
            throw new BadRequestException("知识库ID不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择要上传的文档");
        }
        if (file.getSize() > this.parsingProperties.getMaxFileSize().toBytes()) {
            throw new BadRequestException("文档超过最大限制：" + this.parsingProperties.getMaxFileSize());
        }

        var originalFileName = normalizeOriginalFileName(file.getOriginalFilename());
        var extension = extensionOf(originalFileName);
        if (!this.parsingProperties.supports(extension)) {
            throw new BadRequestException("不支持的文档类型：" + extension
                    + "，仅支持 " + this.parsingProperties.getAllowedExtensions());
        }
        var resolvedDocumentName = resolveDocumentName(documentName, originalFileName);
        return new UploadFileMetadata(originalFileName, resolvedDocumentName, extension);
    }

    private String calculateHash(MultipartFile file) {
        try (var inputStream = file.getInputStream()) {
            // 第一遍读取文件并计算哈希
            return this.fileHasher.sha256(inputStream);
        } catch (IOException e) {
            throw new CommonException("读取上传文件失败", e);
        }
    }

    private String createStorageKey(Long libraryId, String extension) {
        // 使用当前日期生成目录路径
        var datePath = LocalDate.now().format(DATE_PATH_FORMATTER);

        // 使用无连字符的 UUID 生成文件名
        var fileId = UUID.randomUUID().toString().replace("-", "");

        // 组合知识库、日期和文件名生成存储地址
        return String.format("%s/%d/%s/%s.%s", STORAGE_PREFIX, libraryId, datePath, fileId, extension);
    }

    private String normalizeOriginalFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new BadRequestException("文档名称不能为空");
        }
        if (originalFileName.indexOf('\r') >= 0 || originalFileName.indexOf('\n') >= 0) {
            throw new BadRequestException("文档名称包含非法字符");
        }
        // 只保留路径中的最后一段文件名
        var normalized = originalFileName.replace('\\', '/');
        var fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isBlank() || fileName.length() > 255) {
            throw new BadRequestException("文档名称长度不合法");
        }
        return fileName;
    }

    private String extensionOf(String fileName) {
        var dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "unknown";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeFileType(String extension) {
        // 将 markdown 扩展名统一保存为 MD
        return "markdown".equals(extension) ? "MD" : extension.toUpperCase(Locale.ROOT);
    }

    private String resolveDocumentName(String documentName, String originalFileName) {
        var resolved = documentName;
        if (resolved == null || resolved.isBlank()) {
            var dotIndex = originalFileName.lastIndexOf('.');
            resolved = dotIndex > 0 ? originalFileName.substring(0, dotIndex) : originalFileName;
        }
        resolved = resolved.trim();
        if (resolved.isEmpty() || resolved.length() > 200) {
            throw new BadRequestException("文档标题长度不合法");
        }
        return resolved;
    }

    private void deleteUploadedFile(String storageKey, RuntimeException cause) {
        try {
            this.fileStorage.deleteFile(storageKey);
        } catch (RuntimeException cleanupException) {
            cause.addSuppressed(cleanupException);
            log.error("清理上传文件失败，storageKey={}", storageKey, cleanupException);
        }
    }

    private record UploadFileMetadata(String originalFileName, String documentName, String extension) {
    }
}
