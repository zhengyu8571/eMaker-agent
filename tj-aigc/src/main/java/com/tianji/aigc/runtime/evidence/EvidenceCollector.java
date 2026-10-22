package com.tianji.aigc.runtime.evidence;

import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.aigc.runtime.model.CapabilityFailureCode;
import com.tianji.aigc.runtime.model.EvidenceSnapshot;

import java.util.Map;

/**
 * 收集一次Agent运行产生的能力调用证据。
 */
public interface EvidenceCollector {

    void record(String invocationId,
                String stepId,
                CapabilityDescriptor descriptor,
                Map<String, Object> input,
                CapabilityResult result,
                CapabilityFailureCode failureCode,
                Map<String, Object> runtimeMetadata);

    EvidenceSnapshot snapshot();

    EvidenceSnapshot freeze();

    boolean isFrozen();
}
