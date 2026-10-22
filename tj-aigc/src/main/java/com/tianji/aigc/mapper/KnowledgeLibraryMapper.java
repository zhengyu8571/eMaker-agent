package com.tianji.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.aigc.entity.KnowledgeLibrary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写知识库记录。
 */
@Mapper
public interface KnowledgeLibraryMapper extends BaseMapper<KnowledgeLibrary> {
}
