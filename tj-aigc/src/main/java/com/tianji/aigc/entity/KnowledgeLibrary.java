package com.tianji.aigc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_library")
public class KnowledgeLibrary implements Serializable {

    // 知识库主键
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 供 Skill 和检索过滤使用的稳定标识，名称修改时不改变。
     */
    private String libraryKey;

    // 知识库名称
    private String name;

    // 知识库说明
    private String description;

    /**
     * 0：禁用，1：启用。
     */
    private Integer status;

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
