package com.tianji.aigc.capability.model;

import com.tianji.aigc.capability.Capability;
import com.tianji.aigc.capability.enums.CapabilityKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 保存一次请求经过Skill白名单裁剪后的能力视图。
 */
public record ResolvedCapabilities(Map<String, Capability> capabilities) {

    public ResolvedCapabilities {
        capabilities = capabilities == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }

    public Optional<Capability> find(String capabilityKey) {
        return Optional.ofNullable(capabilities.get(capabilityKey));
    }

    public Capability require(String capabilityKey) {
        return find(capabilityKey).orElseThrow(() ->
                new CapabilityAccessDeniedException(
                        "当前Skill未授权Capability：" + capabilityKey));
    }

    public List<CapabilityDescriptor> descriptors() {
        return capabilities.values().stream()
                .map(Capability::descriptor)
                .toList();
    }

    public List<Capability> byKind(CapabilityKind kind) {
        return capabilities.values().stream()
                .filter(capability -> capability.descriptor().kind() == kind)
                .toList();
    }
}
