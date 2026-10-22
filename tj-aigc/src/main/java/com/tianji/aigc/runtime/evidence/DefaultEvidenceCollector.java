package com.tianji.aigc.runtime.evidence;

import com.tianji.aigc.capability.model.CapabilityDescriptor;
import com.tianji.aigc.capability.model.CapabilityResult;
import com.tianji.aigc.dto.SourceRef;
import com.tianji.aigc.runtime.model.CapabilityEvidence;
import com.tianji.aigc.runtime.model.CapabilityFailureCode;
import com.tianji.aigc.runtime.model.EvidenceSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在线程之间安全收集并冻结一次运行的证据。
 */
public class DefaultEvidenceCollector implements EvidenceCollector {

    // 负责证据输入脱敏和输出裁剪
    private final EvidenceSanitizer sanitizer;

    // 按完成顺序保存每次能力调用证据
    private final ArrayList<CapabilityEvidence> evidence = new ArrayList<>();

    // 按首次出现顺序保存去重后的来源
    private final LinkedHashMap<String, SourceRef> sources = new LinkedHashMap<>();

    // 标记本次运行的证据是否已经结束收集
    private boolean frozen;

    public DefaultEvidenceCollector(EvidenceSanitizer sanitizer) {
        // 保存统一的证据清理组件
        this.sanitizer = sanitizer;
    }

    @Override
    public synchronized void record(String invocationId,
                                    String stepId,
                                    CapabilityDescriptor descriptor,
                                    Map<String, Object> input,
                                    CapabilityResult result,
                                    CapabilityFailureCode failureCode,
                                    Map<String, Object> runtimeMetadata) {
        // 冻结后不再接受迟到的能力结果
        if (this.frozen) {
            // 暴露错误生命周期调用
            throw new IllegalStateException("Agent运行证据已经冻结");
        }
        // 校验能力描述对象
        if (descriptor == null) {
            // 拒绝缺少能力身份的证据
            throw new IllegalArgumentException("能力描述不能为空");
        }
        // 读取能力返回的业务输出
        var output = result == null ? null : result.output();
        // 读取能力返回的可信来源
        var resultSources = result == null ? java.util.List.<SourceRef>of() : result.sources();
        // 合并能力元数据和运行时元数据
        var metadata = mergeMetadata(result, runtimeMetadata);
        // 构造不可修改的能力证据
        var item = new CapabilityEvidence(
                invocationId,
                stepId,
                descriptor.key(),
                descriptor.kind(),
                this.sanitizer.sanitizeInput(input),
                this.sanitizer.snapshotOutput(output),
                resultSources,
                this.sanitizer.sanitizeMetadata(metadata),
                failureCode);
        // 保存本次能力调用证据
        this.evidence.add(item);
        // 汇总并去重本次能力引用来源
        for (var source : resultSources) {
            // 忽略空的引用来源
            if (source == null) {
                // 继续处理下一个引用来源
                continue;
            }
            // 相同稳定来源只保留第一次出现的记录
            this.sources.putIfAbsent(sourceKey(source), source);
        }
    }

    @Override
    public synchronized EvidenceSnapshot snapshot() {
        // 复制当前证据和来源生成只读快照
        return new EvidenceSnapshot(new ArrayList<>(this.evidence), new ArrayList<>(this.sources.values()));
    }

    @Override
    public synchronized EvidenceSnapshot freeze() {
        // 阻止后续能力结果继续写入
        this.frozen = true;
        // 返回冻结时的最终证据快照
        return snapshot();
    }

    @Override
    public synchronized boolean isFrozen() {
        // 返回当前冻结状态
        return this.frozen;
    }

    private Map<String, Object> mergeMetadata(CapabilityResult result, Map<String, Object> runtimeMetadata) {
        // 按插入顺序保存合并后的元数据
        var merged = new LinkedHashMap<String, Object>();
        // 能力有结果时先复制能力自身元数据
        if (result != null) {
            // 写入能力返回的元数据
            merged.putAll(result.metadata());
        }
        // 运行时有元数据时覆盖同名字段
        if (runtimeMetadata != null) {
            // 写入运行时测量的元数据
            merged.putAll(runtimeMetadata);
        }
        // 返回合并后的元数据
        return merged;
    }

    private String sourceKey(SourceRef source) {
        // 优先使用跨排序保持稳定的Chunk ID
        if (source.chunkId() != null && !source.chunkId().isBlank()) {
            // 返回Chunk维度的去重键
            return "chunk:" + source.chunkId().strip();
        }
        // 缺少Chunk ID时组合来源字段生成稳定键
        return "source:" + String.valueOf(source.libraryKey()) + ":" + String.valueOf(source.documentId()) + ":" + String.valueOf(source.documentVersion()) + ":" + String.valueOf(source.citationId());
    }
}
