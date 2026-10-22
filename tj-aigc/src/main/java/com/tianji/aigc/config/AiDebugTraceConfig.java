package com.tianji.aigc.config;

import com.tianji.aigc.debug.AiDebugModelObserver;
import com.tianji.aigc.debug.AiDebugTrace;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 复用Spring AI模型观测入口，只新增本地调试文件记录。 */
@Configuration
public class AiDebugTraceConfig {
    @Bean
    @ConditionalOnMissingBean(ObservationRegistry.class)
    public ObservationRegistry aiObservationRegistry() {
        // 没有现成注册表时提供给模型，避免SDK默认使用NOOP
        return ObservationRegistry.create();
    }

    @Bean
    public SmartInitializingSingleton registerAiDebugObserver(ObservationRegistry registry, AiDebugTrace trace) {
        // Bean完成装配后挂载处理器，不包装模型Bean或引入循环依赖
        return () -> registry.observationConfig().observationHandler(new AiDebugModelObserver(trace));
    }
}
