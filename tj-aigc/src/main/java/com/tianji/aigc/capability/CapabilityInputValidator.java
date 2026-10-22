package com.tianji.aigc.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.model.CapabilityDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 在能力执行前校验JSON Schema中的必填字段和基础类型。
 */
@Component
@RequiredArgsConstructor
public class CapabilityInputValidator {

    private final ObjectMapper objectMapper;

    public void validate(CapabilityDescriptor descriptor,
                         Map<String, Object> input) {
        try {
            var schema = this.objectMapper.readTree(descriptor.inputSchema());
            var inputNode = this.objectMapper.valueToTree(
                    input == null ? Map.of() : input);
            validateRequired(descriptor.key(), schema, inputNode);
            validatePropertyTypes(descriptor.key(), schema, inputNode);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Capability输入校验失败：" + descriptor.key(), e);
        }
    }

    private void validateRequired(String capabilityKey,
                                  JsonNode schema,
                                  JsonNode input) {
        var required = schema.path("required");
        if (!required.isArray()) {
            return;
        }
        for (var field : required) {
            var fieldName = field.asText();
            if (!input.hasNonNull(fieldName)) {
                throw new IllegalArgumentException(
                        "Capability缺少必填参数：" + capabilityKey + "." + fieldName);
            }
        }
    }

    private void validatePropertyTypes(String capabilityKey,
                                       JsonNode schema,
                                       JsonNode input) {
        var properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        var fields = properties.fields();
        while (fields.hasNext()) {
            var property = fields.next();
            var value = input.get(property.getKey());
            if (value == null || value.isNull()) {
                continue;
            }
            var expectedType = property.getValue().path("type").asText();
            if (!matchesType(value, expectedType)) {
                throw new IllegalArgumentException(
                        "Capability参数类型错误：" + capabilityKey + "."
                                + property.getKey() + "应为" + expectedType);
            }
        }
    }

    private boolean matchesType(JsonNode value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() || isSerializedLong(value);
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private boolean isSerializedLong(JsonNode value) {
        // 只兼容项目 Jackson 对 Long 的十进制字符串序列化
        if (!value.isTextual() || !value.textValue().matches("-?(0|[1-9][0-9]*)")) {
            // 任意文本仍不满足整数契约
            return false;
        }
        try {
            // 超出 Long 范围的数字字符串也不能传给课程工具
            Long.parseLong(value.textValue());
            // 合法的整数字符串可进入官方 ToolCallback 转换
            return true;
        } catch (NumberFormatException exception) {
            // 避免大整数溢出后被误当成合法输入
            return false;
        }
    }
}
