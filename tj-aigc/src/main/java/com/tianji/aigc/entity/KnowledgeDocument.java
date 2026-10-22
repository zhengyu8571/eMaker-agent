package com.tianji.aigc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tianji.aigc.enums.KnowledgeDocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档及其当前版本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_document")
public class KnowledgeDocument implements Serializable {

    // 文档主键
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 文档所属知识库ID
    private Long libraryId;

    // 用户看到的文档标题
    private String documentName;

    // 上传时保留的原始文件名
    private String originalFileName;

    // 统一后的文件类型
    private String fileType;

    // 上传请求携带的内容类型
    private String contentType;

    // 原文件在对象存储中的地址
    private String storageKey;

    // 原文件字节数
    private Long fileSize;

    // 原文件 SHA-256 哈希
    private String fileHash;

    /**
     * 每次重新上传或重新解析时递增，异步任务会保存对应版本快照。
     */
    private Integer version;

    // 当前版本的处理状态
    private KnowledgeDocumentStatus status;

    // 当前版本对应的处理任务ID
    private Long currentTaskId;

    // 记录创建时间
    private LocalDateTime createTime;

    // 记录最后更新时间
    private LocalDateTime updateTime;

    // 创建人ID
    @TableField(fill = FieldFill.INSERT)
    private Long creater;

    // 最后修改人ID
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updater;

    // MyBatis-Plus 逻辑删除标记
    @TableLogic
    private Integer deleted;
}
