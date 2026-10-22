package com.tianji.aigc.runtime.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 按已校验的 InputBinding 构造能力的结构化输入。 */
@Component
public class PlanInputBinder {

    // 读取用户字段和上游结构化输出
    private final ObjectMapper objectMapper;

    public PlanInputBinder(ObjectMapper objectMapper) {
        // 保存项目共用的 JSON 转换器
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> bind(PlanStep step, Map<String, Object> userInput, Map<String, StepExecutionResult> results) {
        // 为当前能力创建独立输入对象
        var input = new LinkedHashMap<String, Object>();
        // 按计划逐条填入目标字段
        for (var binding : step.inputBindings()) {
            // 读取该字段声明的来源
            var value = sourceValue(binding, userInput, results);
            // 写入目标的点分隔路径
            put(input, binding.targetPath(), value);
        }
        // 返回当前步骤专属的输入
        return input;
    }

    private Object sourceValue(InputBinding binding, Map<String, Object> userInput, Map<String, StepExecutionResult> results) {
        // 固定值不依赖其他步骤
        if (binding.source() == InputSource.CONSTANT) {
            // 返回计划中的常量值
            return binding.constantValue();
        }
        // 用户字段从原始结构化输入读取
        if (binding.source() == InputSource.USER_INPUT) {
            // 只读取声明的用户字段
            return valueAt(userInput, binding.sourcePath());
        }
        // 查找声明的上游步骤结果
        var upstream = results.get(binding.sourceStepId().strip());
        // 上游必须已经成功
        if (upstream == null || upstream.status() != StepStatus.SUCCEEDED || upstream.capabilityResult() == null) {
            // 阻止从失败或未运行步骤读取数据
            throw new IllegalArgumentException("上游步骤未成功：" + binding.sourceStepId());
        }
        // 只读取 CapabilityResult.output 中声明的字段
        return valueAt(upstream.capabilityResult().result().output(), binding.sourcePath());
    }

    private Object valueAt(Object source, String path) {
        // 转为 JSON 树以统一处理 Map 和 record 输出
        JsonNode value = this.objectMapper.valueToTree(source);
        // 逐层进入点分隔字段
        for (var segment : path.strip().split("\\.", -1)) {
            // 缺少字段不能退化为整段输出
            if (segment.isBlank() || value == null || !value.has(segment)) {
                // 明确报告无法绑定的字段
                throw new IllegalArgumentException("输入来源字段不存在：" + path);
            }
            // 进入下一级字段
            value = value.get(segment);
        }
        // 空值不能填入经过类型校验的字段
        if (value == null || value.isNull()) {
            // 拒绝运行期缺失的上游输出
            throw new IllegalArgumentException("输入来源字段为空：" + path);
        }
        // 转成能力输入使用的普通 Java 值
        return this.objectMapper.convertValue(value, Object.class);
    }

    @SuppressWarnings("unchecked")
    private void put(Map<String, Object> input, String path, Object value) {
        // 按点分隔目标字段
        var segments = path.strip().split("\\.", -1);
        // 从能力输入根对象开始写入
        var current = input;
        // 逐层创建嵌套对象
        for (var index = 0; index < segments.length - 1; index++) {
            // 读取当前路径段
            var segment = segments[index];
            // 复用已创建的嵌套对象
            var nested = current.computeIfAbsent(segment, key -> new LinkedHashMap<String, Object>());
            // 标量不能同时充当对象
            if (!(nested instanceof Map)) {
                // 拒绝互相覆盖的目标路径
                throw new IllegalArgumentException("目标字段路径冲突：" + path);
            }
            // 进入下一层对象
            current = (Map<String, Object>) nested;
        }
        // 一个目标字段只写入一次
        if (current.putIfAbsent(segments[segments.length - 1], value) != null) {
            // 拒绝重复覆盖输入字段
            throw new IllegalArgumentException("目标字段重复绑定：" + path);
        }
    }
}
