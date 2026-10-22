package com.tianji.aigc.config;

import cn.hutool.core.lang.Assert;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具结果保持器，用来存储tools中得到的结果，请求id 作为key， value为键值对数据
 *
 * @author zzj
 * @version 1.0
 */
public class ToolResultHolder {

    private static final Map<String, Map<String, Object>> HANDLER_MAP = new ConcurrentHashMap<>();


    /**
     * 工具类，禁止实例化
     */
    private ToolResultHolder() {
    }

    public static void put(String key, String field, Object result) {
        // 校验请求或消息标识
        Assert.notNull(key, "key is not null!");
        // 校验结果字段名称
        Assert.notNull(field, "field is not null!");
        // 使用并发Map保存同一请求的并行工具结果
        HANDLER_MAP.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, result);
    }

    public static Map<String, Object> get(String key) {
        return key == null ? null : HANDLER_MAP.get(key);
    }

    public static Object get(String key, String field) {
        Assert.notNull(key, "key is not null!");
        Assert.notNull(field, "field is not null!");
        return Optional.ofNullable(HANDLER_MAP.get(key))
                .map(map -> map.get(field))
                .orElse(null);
    }

    public static void remove(String key) {
        // 空标识没有需要清理的结果
        if (key == null) {
            // 直接结束本次清理
            return;
        }
        // 删除请求对应的全部临时结果
        HANDLER_MAP.remove(key);
    }

}
