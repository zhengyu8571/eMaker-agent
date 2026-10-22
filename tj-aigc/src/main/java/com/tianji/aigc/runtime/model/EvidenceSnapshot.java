package com.tianji.aigc.runtime.model;

import com.tianji.aigc.dto.SourceRef;

import java.util.List;

/**
 * 保存某一时刻不可修改的运行证据快照。
 */
public record EvidenceSnapshot(

        // 保存已经完成的能力调用证据
        List<CapabilityEvidence> capabilityEvidence,

        // 保存所有能力汇总后的引用来源
        List<SourceRef> sources
) {

    // 提供不含任何证据的公共快照
    public static final EvidenceSnapshot EMPTY = new EvidenceSnapshot(List.of(), List.of());

    public EvidenceSnapshot {
        // 固定能力证据的只读快照
        capabilityEvidence = capabilityEvidence == null ? List.of() : List.copyOf(capabilityEvidence);
        // 固定引用来源的只读快照
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
