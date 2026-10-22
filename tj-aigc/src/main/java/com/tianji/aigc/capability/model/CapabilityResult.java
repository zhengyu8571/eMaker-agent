package com.tianji.aigc.capability.model;

import com.tianji.aigc.dto.SourceRef;

import java.util.List;
import java.util.Map;

/**
 * 统一保存能力输出、引用来源和附加元数据。
 */
public record CapabilityResult(

        // 能力返回的业务结果
        Object output,

        // 能力执行过程中产生的可信来源
        List<SourceRef> sources,

        // 不参与业务结果的耗时、降级等附加信息
        Map<String, Object> metadata
) {

    public CapabilityResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static CapabilityResult of(Object output) {
        return new CapabilityResult(output, List.of(), Map.of());
    }
}
