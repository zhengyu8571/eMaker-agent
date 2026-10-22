package com.tianji.aigc.runtime.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 脱敏并限制写入运行证据的数据大小。
 */
@Component
public class EvidenceSanitizer {

    // 限制证据对象的最大递归层数
    private static final int MAX_DEPTH = 5;

    // 限制单个集合写入证据的元素数量
    private static final int MAX_ITEMS = 20;

    // 限制单段文本写入证据的字符数量
    private static final int MAX_TEXT_LENGTH = 1000;

    // 定义需要隐藏的参数名称片段
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password", "passwd", "secret", "token", "authorization", "accesskey", "apikey", "privatekey", "credential");

    // 保存对象转换所需的Jackson组件
    private final ObjectMapper objectMapper;

    public EvidenceSanitizer(ObjectMapper objectMapper) {
        // 保存项目统一配置的对象转换器
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> sanitizeInput(Map<String, Object> input) {
        // 空参数直接返回只读空Map
        if (input == null || input.isEmpty()) {
            // 返回统一的空参数快照
            return Map.of();
        }
        // 从第一层开始处理输入参数
        return sanitizeMap(input, 0);
    }

    public Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        // 复用输入参数的脱敏和裁剪规则
        return sanitizeInput(metadata);
    }

    public Object snapshotOutput(Object output) {
        // 从第一层开始裁剪能力输出
        return sanitizeValue(output, 0);
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> source, int depth) {
        // 超出层数后只保留截断标记
        if (depth >= MAX_DEPTH) {
            // 返回不可修改的截断结果
            return Map.of("truncated", true);
        }
        // 按原顺序保存清理后的字段
        var sanitized = new LinkedHashMap<String, Object>();
        // 记录已经处理的字段数量
        var count = 0;
        // 逐项处理Map字段
        for (var entry : source.entrySet()) {
            // 达到数量上限后停止继续复制
            if (count >= MAX_ITEMS) {
                // 标记剩余字段已经被裁剪
                sanitized.put("truncated", true);
                // 结束本次Map遍历
                break;
            }
            // 将字段名统一转换为文本
            var key = String.valueOf(entry.getKey());
            // 敏感字段只保存固定掩码
            var value = isSensitiveKey(key) ? "***" : sanitizeValue(entry.getValue(), depth + 1);
            // 保存清理后的字段和值
            sanitized.put(key, value);
            // 累加已经处理的字段数量
            count++;
        }
        // 返回保持字段顺序的只读Map
        return Collections.unmodifiableMap(sanitized);
    }

    private Object sanitizeValue(Object value, int depth) {
        // 使用文本标记表示原始空值
        if (value == null) {
            // 避免只读Map拒绝保存null
            return "null";
        }
        // 超出层数后停止展开对象
        if (depth >= MAX_DEPTH) {
            // 返回统一的层数截断标记
            return "[truncated]";
        }
        // 文本只需要限制最大长度
        if (value instanceof CharSequence text) {
            // 返回裁剪后的普通字符串
            return truncateText(text.toString());
        }
        // 数字和布尔值可以直接写入证据
        if (value instanceof Number || value instanceof Boolean) {
            // 保留简单值本身
            return value;
        }
        // 枚举使用稳定名称保存
        if (value instanceof Enum<?> enumValue) {
            // 返回枚举常量名称
            return enumValue.name();
        }
        // 时间对象使用标准文本保存
        if (value instanceof TemporalAccessor) {
            // 返回时间对象的标准字符串
            return value.toString();
        }
        // Map按字段递归脱敏
        if (value instanceof Map<?, ?> map) {
            // 返回清理后的只读Map
            return sanitizeMap(map, depth);
        }
        // 集合按顺序递归裁剪
        if (value instanceof Collection<?> collection) {
            // 返回清理后的只读列表
            return sanitizeCollection(collection, depth);
        }
        // 数组按下标递归裁剪
        if (value.getClass().isArray()) {
            // 返回清理后的只读列表
            return sanitizeArray(value, depth);
        }
        try {
            // 将普通对象转换成可遍历的Jackson结构
            var converted = this.objectMapper.convertValue(value, Object.class);
            // 避免异常转换器返回原对象造成递归
            if (converted == value) {
                // 退化为经过长度限制的文本
                return truncateText(String.valueOf(value));
            }
            // 继续清理转换后的普通结构
            return sanitizeValue(converted, depth + 1);
        } catch (IllegalArgumentException exception) {
            // 无法转换时只保留受限文本快照
            return truncateText(String.valueOf(value));
        }
    }

    private List<Object> sanitizeCollection(Collection<?> source, int depth) {
        // 提前限制列表的初始容量
        var sanitized = new ArrayList<Object>(Math.min(source.size(), MAX_ITEMS) + 1);
        // 记录已经处理的元素数量
        var count = 0;
        // 按原顺序处理集合元素
        for (var item : source) {
            // 达到数量上限后停止继续复制
            if (count >= MAX_ITEMS) {
                // 标记剩余元素已经被裁剪
                sanitized.add("[truncated]");
                // 结束本次集合遍历
                break;
            }
            // 保存清理后的集合元素
            sanitized.add(sanitizeValue(item, depth + 1));
            // 累加已经处理的元素数量
            count++;
        }
        // 返回不可修改的列表快照
        return List.copyOf(sanitized);
    }

    private List<Object> sanitizeArray(Object source, int depth) {
        // 读取数组的实际长度
        var length = Array.getLength(source);
        // 计算本次允许复制的元素数量
        var limitedLength = Math.min(length, MAX_ITEMS);
        // 为受限数组准备结果列表
        var sanitized = new ArrayList<Object>(limitedLength + 1);
        // 按下标处理允许复制的数组元素
        for (var index = 0; index < limitedLength; index++) {
            // 保存清理后的数组元素
            sanitized.add(sanitizeValue(Array.get(source, index), depth + 1));
        }
        // 判断原数组是否还有未复制元素
        if (length > limitedLength) {
            // 标记剩余数组元素已经被裁剪
            sanitized.add("[truncated]");
        }
        // 返回不可修改的列表快照
        return List.copyOf(sanitized);
    }

    private boolean isSensitiveKey(String key) {
        // 使用固定语言环境统一字段名大小写
        var normalizedKey = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        // 判断字段名是否包含任一敏感片段
        return SENSITIVE_KEYWORDS.stream().anyMatch(normalizedKey::contains);
    }

    private String truncateText(String value) {
        // 未超过上限时保留完整文本
        if (value.length() <= MAX_TEXT_LENGTH) {
            // 返回原始文本
            return value;
        }
        // 截取上限内文本并添加裁剪标记
        return value.substring(0, MAX_TEXT_LENGTH) + "...[truncated]";
    }
}
