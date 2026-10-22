package com.tianji.aigc.capability.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.Capability;
import com.tianji.aigc.capability.CapabilityProvider;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.aigc.tools.CourseSearchTools;
import com.tianji.aigc.tools.OrderTools;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 使用Spring AI官方适配器读取现有@Tool方法。
 */
@Component
public class LocalToolCapabilityProvider implements CapabilityProvider {

    private final List<Capability> capabilities;

    public LocalToolCapabilityProvider(CourseTools courseTools,
                                       OrderTools orderTools,
                                       CourseSearchTools courseSearchTools,
                                       ObjectMapper objectMapper) {
        var callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(courseTools, orderTools, courseSearchTools)
                .build()
                .getToolCallbacks();
        this.capabilities = Arrays.stream(callbacks)
                .sorted(Comparator.comparing(callback -> callback.getToolDefinition().name()))
                .map(callback -> new LocalToolCapabilityAdapter(
                        callback,
                        sideEffect(callback.getToolDefinition().name()),
                        true,
                        objectMapper))
                .map(Capability.class::cast)
                .toList();
    }

    @Override
    public List<Capability> capabilities() {
        return this.capabilities;
    }

    private SideEffectLevel sideEffect(String toolName) {
        // 现有课程工具和新候选查询都只读取数据
        return switch (toolName) {
            case "queryCourseById", "prePlaceOrder", "searchCourseCandidate" -> SideEffectLevel.READ_ONLY;
            default -> throw new IllegalStateException("本地工具缺少副作用声明：" + toolName);
        };
    }
}
