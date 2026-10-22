package com.tianji.aigc.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 在应用启动期注册并校验全部统一能力。
 */
@Component
public class CapabilityRegistry {

    private final Map<String, Capability> capabilities;

    public CapabilityRegistry(List<Capability> capabilityList,
                              List<CapabilityProvider> capabilityProviders,
                              ObjectMapper objectMapper) {
        var registered = new LinkedHashMap<String, Capability>();
        if (capabilityList != null) {
            for (var capability : capabilityList) {
                register(registered, capability, objectMapper);
            }
        }
        if (capabilityProviders != null) {
            for (var provider : capabilityProviders) {
                if (provider == null || provider.capabilities() == null) {
                    throw new IllegalStateException("CapabilityProvider返回值不能为空");
                }
                for (var capability : provider.capabilities()) {
                    register(registered, capability, objectMapper);
                }
            }
        }
        // 启动完成后固定能力目录，禁止运行时被外部代码修改
        this.capabilities = Collections.unmodifiableMap(registered);
    }

    public Optional<Capability> find(String capabilityKey) {
        if (capabilityKey == null || capabilityKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.capabilities.get(capabilityKey.strip()));
    }

    public Capability require(String capabilityKey) {
        return find(capabilityKey).orElseThrow(() ->
                new IllegalArgumentException("未注册的Capability：" + capabilityKey));
    }

    public List<CapabilityDescriptor> descriptors() {
        return this.capabilities.values().stream()
                .map(Capability::descriptor)
                .toList();
    }

    private void register(Map<String, Capability> registered,
                          Capability capability,
                          ObjectMapper objectMapper) {
        if (capability == null) {
            throw new IllegalStateException("Capability实例不能为空");
        }
        var descriptor = capability.descriptor();
        if (descriptor == null) {
            throw new IllegalStateException(
                    "Capability描述不能为空：" + capability.getClass().getName());
        }
        validateSchema(descriptor.key(), "输入", descriptor.inputSchema(), objectMapper);
        validateSchema(descriptor.key(), "输出", descriptor.outputSchema(), objectMapper);

        var existing = registered.putIfAbsent(descriptor.key(), capability);
        if (existing != null) {
            throw new IllegalStateException("Capability key重复注册："
                    + descriptor.key()
                    + "，冲突类："
                    + existing.getClass().getName()
                    + "、"
                    + capability.getClass().getName());
        }
    }

    private void validateSchema(String capabilityKey,
                                String schemaType,
                                String schema,
                                ObjectMapper objectMapper) {
        try {
            // 当前阶段只校验JSON对象语法，具体参数匹配由调用器步骤实现
            var schemaNode = objectMapper.readTree(schema);
            if (schemaNode == null || !schemaNode.isObject()) {
                throw new IllegalStateException("Capability"
                        + schemaType
                        + "Schema必须是JSON对象："
                        + capabilityKey);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Capability"
                    + schemaType
                    + "Schema不是合法JSON："
                    + capabilityKey, e);
        }
    }
}
