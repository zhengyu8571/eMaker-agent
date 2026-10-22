package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 控制Skill动态装配是否接管DIRECT聊天链。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.skill")
public class SkillFeatureProperties {

    // 关闭后恢复阶段三的Agent提示词、知识库范围和原始工具注册方式
    private boolean enabled = true;
}
