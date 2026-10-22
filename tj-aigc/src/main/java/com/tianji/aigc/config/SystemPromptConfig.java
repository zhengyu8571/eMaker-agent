package com.tianji.aigc.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
@Configuration
@RequiredArgsConstructor
public class SystemPromptConfig {

    private final NacosConfigManager nacosConfigManager;
    private final AIProperties aiProperties;

    // 使用原子引用，保证线程安全
    private final AtomicReference<String> routeAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> recommendAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> buyAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> consultAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> knowledgeAgentSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> queryRewriteSystemMessage = new AtomicReference<>();
    private final AtomicReference<String> textSystemMessage = new AtomicReference<>();

    @PostConstruct // 初始化时加载配置
    public void init() {
        // 读取配置文件
        loadConfig(aiProperties.getSystem().getRouteAgent(), routeAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getRecommendAgent(), recommendAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getBuyAgent(), buyAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getConsultAgent(), consultAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getKnowledgeAgent(), knowledgeAgentSystemMessage);
        loadConfig(aiProperties.getSystem().getQueryRewrite(), queryRewriteSystemMessage);
        loadConfig(aiProperties.getSystem().getText(), textSystemMessage);
    }

    private void loadConfig(AIProperties.System.Chat chatConfig, AtomicReference<String> target) {
        try {
            var dataId = chatConfig.getDataId();
            var group = chatConfig.getGroup();
            var timeoutMs = chatConfig.getTimeoutMs();

            // 读取配置文件中的内容
            var config = nacosConfigManager.getConfigService().getConfig(dataId, group, timeoutMs);
            target.set(config);
            log.info("读取提示词配置成功，dataId={}", dataId);

            // 设置监听事件，用于热更新
            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String info) {
                    target.set(info);
                    log.info("更新提示词配置成功，dataId={}", dataId);
                }
            });
        } catch (Exception e) {
            log.error("加载配置失败", e);
        }
    }

}
