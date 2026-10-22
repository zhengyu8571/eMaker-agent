package com.tianji.aigc.runtime.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.CapabilityRegistry;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 在执行前确定性校验计划结构、权限、依赖和字段绑定。 */
@Component
public class PlanValidator {

    // 查询全局能力目录以区分未知能力和未授权能力
    private final CapabilityRegistry capabilityRegistry;
    // 解析能力输入输出 Schema 和用户字段
    private final ObjectMapper objectMapper;

    public PlanValidator(CapabilityRegistry capabilityRegistry, ObjectMapper objectMapper) {
        // 保存全局能力目录
        this.capabilityRegistry = capabilityRegistry;
        // 保存 JSON 转换器
        this.objectMapper = objectMapper;
    }

    public void validate(ExecutionPlan plan, AgentExecutionContext context, Map<String, Object> userInput) {
        // 首次执行没有可复用步骤
        validate(plan, context, userInput, Set.of());
    }

    public void validate(ExecutionPlan plan, AgentExecutionContext context, Map<String, Object> userInput, Set<String> reusableStepIds) {
        // 运行上下文必须来自当前请求
        if (context == null) {
            // 拒绝缺少权限和预算的校验
            throw new IllegalArgumentException("计划校验缺少运行上下文");
        }
        // 保存本次发现的全部确定性错误
        var errors = new ArrayList<String>();
        // 计划不能为空
        if (plan == null) {
            // 返回可供 Planner 修复的错误
            throw new PlanValidationException(List.of("执行计划不能为空"));
        }
        // 用户目标不能为空
        if (blank(plan.goal())) {
            // 记录缺失的目标
            errors.add("计划 goal 不能为空");
        }
        // 至少需要一个执行步骤
        if (plan.steps().isEmpty()) {
            // 记录缺失的步骤
            errors.add("计划 steps 不能为空");
        }
        // 使用后端预算限制计划步骤数
        // 重规划只对尚需执行的步骤消耗步骤预算
        var reusable = reusableStepIds == null ? Set.<String>of() : reusableStepIds;
        // 可复用步骤不能多于计划本身
        if (plan.steps().size() - reusable.size() > context.budget().remainingSteps()) {
            // 拒绝 Planner 输出超出剩余预算的步骤
            errors.add("计划步骤数超过剩余 AgentBudget.maxSteps");
            // 在遍历不受信任的大计划前直接终止
            throw new PlanValidationException(errors);
        }
        // 按步骤 ID 保存本次计划
        var stepsById = new LinkedHashMap<String, PlanStep>();
        // 逐步校验基础字段和唯一性
        for (var step : plan.steps()) {
            // 步骤对象不能为空
            if (step == null) {
                // 记录空步骤并继续检查其他步骤
                errors.add("计划中存在空步骤");
                continue;
            }
            // 步骤 ID 必须有效
            if (blank(step.id())) {
                // 记录缺失的步骤 ID
                errors.add("stepId 不能为空");
                continue;
            }
            // 清理步骤 ID 的首尾空白
            var stepId = step.id().strip();
            // 拒绝重复步骤 ID
            if (stepsById.putIfAbsent(stepId, step) != null) {
                // 记录重复的步骤 ID
                errors.add("stepId 重复：" + stepId);
            }
            // 能力键必须有效
            if (blank(step.capabilityKey())) {
                // 记录缺失的能力键
                errors.add("capabilityKey 不能为空：" + stepId);
            }
        }
        // 声称复用的步骤必须仍存在于新计划
        if (!stepsById.keySet().containsAll(reusable)) {
            // 拒绝用不存在的步骤抵扣预算
            errors.add("可复用步骤不在新计划中");
        }
        // 逐步验证依赖和能力权限
        for (var step : stepsById.values()) {
            // 校验每个依赖是否存在且不是自身
            validateDependencies(step, stepsById, errors);
            // 校验能力是否注册、授权、可规划且只读
            validateCapability(step, context, errors);
        }
        // 无效依赖图不能进入字段绑定校验
        if (hasCycle(stepsById)) {
            // 记录依赖环
            errors.add("计划 dependsOn 存在循环依赖");
        }
        // 基础结构无效时不读取可能不存在的 Schema
        if (!errors.isEmpty()) {
            // 一次返回全部已发现的基础错误
            throw new PlanValidationException(errors);
        }
        // 固定用户输入快照供类型校验
        var userNode = this.objectMapper.valueToTree(userInput == null ? Map.of() : userInput);
        // 逐步校验输入绑定及必填字段
        for (var step : stepsById.values()) {
            // 获取已授权的目标能力
            var capability = context.capabilities().require(step.capabilityKey().strip());
            // 解析目标能力的输入 Schema
            var inputSchema = schema(capability.descriptor().inputSchema(), step.id(), errors);
            // 记录当前步骤已绑定的目标字段
            var boundTargets = new HashSet<String>();
            // 遍历当前步骤声明的输入绑定
            for (var binding : step.inputBindings()) {
                // 校验一条绑定的路径和基础类型
                validateBinding(step, binding, inputSchema, stepsById, context, userNode, boundTargets, errors);
                // RAG 的知识库范围只能来自服务端解析的用户输入
                validateRagBinding(capability.descriptor().kind(), step, binding, errors);
            }
            // 必填输入必须能够由绑定提供
            validateRequired(step, inputSchema, boundTargets, errors);
        }
        // 字段绑定错误不能进入执行器
        if (!errors.isEmpty()) {
            // 一次返回全部绑定错误
            throw new PlanValidationException(errors);
        }
    }

    private void validateRagBinding(CapabilityKind kind, PlanStep step, InputBinding binding, List<String> errors) {
        // 非 RAG 能力无需额外限制检索范围
        if (kind != CapabilityKind.RAG || binding == null || binding.targetPath() == null) {
            // 保持其他能力原有绑定逻辑
            return;
        }
        // 模型不得指定其他会话读取聊天历史
        if ("conversationId".equals(binding.targetPath().strip())) {
            // RAG 适配器会用当前运行身份生成会话 ID
            errors.add("RAG 不允许绑定 conversationId：" + step.id());
        }
        // 知识库范围必须使用服务端解析的当前请求字段
        if ("libraryKeys".equals(binding.targetPath().strip()) && (binding.source() != InputSource.USER_INPUT || !"libraryKeys".equals(binding.sourcePath()))) {
            // 拒绝模型用常量或上游输出扩大检索范围
            errors.add("RAG libraryKeys 只能绑定 USER_INPUT.libraryKeys：" + step.id());
        }
    }

    public void validateReplan(ExecutionPlan plan, AgentExecutionContext context, Map<String, Object> userInput, AgentRunState previous) {
        // 只复用上轮真正成功的步骤
        var reusable = previous.stepResults().entrySet().stream().filter(entry -> entry.getValue().status() == StepStatus.SUCCEEDED).map(Map.Entry::getKey).collect(Collectors.toSet());
        // 按剩余预算验证新计划的完整结构
        validate(plan, context, userInput, reusable);
        // 把新计划按步骤 ID 索引
        var current = new HashMap<String, PlanStep>();
        // 保存本次全部步骤
        for (var step : plan.steps()) {
            // ID 唯一性已由基础校验保证
            current.put(step.id().strip(), step);
        }
        // 逐个确认成功步骤没有被删除或修改
        for (var oldStep : previous.plan().steps()) {
            // 只约束准备复用的成功步骤
            if (reusable.contains(oldStep.id().strip()) && !oldStep.equals(current.get(oldStep.id().strip()))) {
                // 阻止模型修改成功结果的来源
                throw new PlanValidationException(List.of("重规划不得修改已成功步骤：" + oldStep.id()));
            }
        }
    }

    private void validateDependencies(PlanStep step, Map<String, PlanStep> stepsById, List<String> errors) {
        // 清理当前步骤 ID
        var stepId = step.id().strip();
        // 保存已出现的依赖 ID
        var seen = new HashSet<String>();
        // 逐个检查显式依赖
        for (var dependency : step.dependsOn()) {
            // 依赖 ID 不能为空
            if (blank(dependency)) {
                // 记录空依赖
                errors.add("dependsOn 包含空 ID：" + stepId);
                continue;
            }
            // 清理依赖 ID
            var dependencyId = dependency.strip();
            // 依赖必须指向已有步骤
            if (!stepsById.containsKey(dependencyId)) {
                // 记录不存在的依赖
                errors.add("dependsOn 引用不存在的步骤：" + stepId + " -> " + dependencyId);
            }
            // 步骤不能依赖自己
            if (stepId.equals(dependencyId)) {
                // 记录自依赖
                errors.add("步骤不能依赖自身：" + stepId);
            }
            // 同一依赖只允许声明一次
            if (!seen.add(dependencyId)) {
                // 记录重复依赖
                errors.add("dependsOn 重复：" + stepId + " -> " + dependencyId);
            }
        }
    }

    private void validateCapability(PlanStep step, AgentExecutionContext context, List<String> errors) {
        // 缺少能力键时基础校验已经记录错误
        if (blank(step.capabilityKey())) {
            // 不再查询空能力键
            return;
        }
        // 清理能力键
        var key = step.capabilityKey().strip();
        // 从全局目录查询能力是否真实存在
        var registered = this.capabilityRegistry.find(key);
        // 未注册能力不能执行
        if (registered.isEmpty()) {
            // 拒绝模型虚构的能力键
            errors.add("capability 不存在：" + key);
            return;
        }
        // 当前 Skill 必须明确授权能力键
        if (!context.skill().allowedCapabilityKeys().contains(key) || context.capabilities().find(key).isEmpty()) {
            // 拒绝跨 Skill 使用能力
            errors.add("capability 未授权：" + key);
            return;
        }
        // 使用全局目录中的稳定能力描述
        var descriptor = registered.get().descriptor();
        // Planner 不能使用隐藏能力
        if (!descriptor.plannerVisible()) {
            // 拒绝服务端专用能力
            errors.add("capability 不对 Planner 可见：" + key);
        }
        // Planner 只能执行只读能力
        if (descriptor.sideEffect() != SideEffectLevel.READ_ONLY) {
            // 拒绝所有写能力
            errors.add("capability 不是只读能力：" + key);
        }
    }

    private boolean hasCycle(Map<String, PlanStep> stepsById) {
        // 保存每个步骤的 DFS 访问状态
        var colors = new HashMap<String, Integer>();
        // 从每个步骤检测依赖环
        for (var stepId : stepsById.keySet()) {
            // 任一分支成环即拒绝整个计划
            if (visit(stepId, stepsById, colors)) {
                // 返回已发现的依赖环
                return true;
            }
        }
        // 所有步骤均无依赖环
        return false;
    }

    private boolean visit(String stepId, Map<String, PlanStep> stepsById, Map<String, Integer> colors) {
        // 正在访问的步骤再次出现代表成环
        if (colors.getOrDefault(stepId, 0) == 1) {
            // 返回环检测结果
            return true;
        }
        // 已完成的步骤无需重复遍历
        if (colors.getOrDefault(stepId, 0) == 2) {
            // 返回无环结果
            return false;
        }
        // 标记当前步骤正在访问
        colors.put(stepId, 1);
        // 遍历已有的依赖步骤
        for (var dependency : stepsById.get(stepId).dependsOn()) {
            // 未知或空依赖已由基础校验处理
            if (blank(dependency) || !stepsById.containsKey(dependency.strip())) {
                // 跳过无效依赖
                continue;
            }
            // 递归检查依赖分支
            if (visit(dependency.strip(), stepsById, colors)) {
                // 返回已发现的依赖环
                return true;
            }
        }
        // 标记当前步骤已完成访问
        colors.put(stepId, 2);
        // 当前分支没有依赖环
        return false;
    }

    private void validateBinding(PlanStep step, InputBinding binding, JsonNode inputSchema, Map<String, PlanStep> stepsById, AgentExecutionContext context, JsonNode userNode, Set<String> boundTargets, List<String> errors) {
        // 绑定对象不能为空
        if (binding == null) {
            // 记录空绑定
            errors.add("inputBindings 包含空绑定：" + step.id());
            return;
        }
        // 目标路径和来源类型必须明确
        if (blank(binding.targetPath()) || binding.source() == null) {
            // 记录不完整的绑定
            errors.add("inputBinding 缺少 targetPath 或 source：" + step.id());
            return;
        }
        // 清理目标路径
        var targetPath = binding.targetPath().strip();
        // 目标字段不能被多次写入
        if (!boundTargets.add(targetPath)) {
            // 记录重复的目标字段
            errors.add("inputBinding 重复绑定目标字段：" + step.id() + "." + targetPath);
        }
        // 目标字段必须由输入 Schema 声明
        var targetSchema = propertySchema(inputSchema, targetPath);
        // 不存在的目标字段不能执行
        if (targetSchema == null) {
            // 记录非法目标字段
            errors.add("targetPath 不存在：" + step.id() + "." + targetPath);
            return;
        }
        // 根据来源种类检查字段与类型
        switch (binding.source()) {
            // 用户输入必须包含明确字段
            case USER_INPUT -> validateUserBinding(step, binding, userNode, targetSchema, errors);
            // 上游输出必须属于依赖闭包且在输出 Schema 中存在
            case STEP_OUTPUT -> validateStepBinding(step, binding, stepsById, context, targetSchema, errors);
            // 固定值必须与目标字段类型兼容
            case CONSTANT -> validateConstantBinding(step, binding, targetSchema, errors);
        }
    }

    private void validateUserBinding(PlanStep step, InputBinding binding, JsonNode userNode, JsonNode targetSchema, List<String> errors) {
        // 用户字段路径不能为空
        if (blank(binding.sourcePath())) {
            // 拒绝把整份用户输入无差别传给能力
            errors.add("USER_INPUT 缺少 sourcePath：" + step.id());
            return;
        }
        // 从用户结构化输入读取具体字段
        var value = valueAtPath(userNode, binding.sourcePath());
        // 用户字段必须存在
        if (value == null || value.isNull()) {
            // 记录不存在的用户字段
            errors.add("USER_INPUT 字段不存在：" + binding.sourcePath());
            return;
        }
        // 用户字段的实际类型必须适配目标 Schema
        if (!matchesValue(value, targetSchema)) {
            // 记录不兼容的用户字段
            errors.add("USER_INPUT 字段类型不兼容：" + binding.sourcePath() + " -> " + binding.targetPath());
        }
    }

    private void validateStepBinding(PlanStep step, InputBinding binding, Map<String, PlanStep> stepsById, AgentExecutionContext context, JsonNode targetSchema, List<String> errors) {
        // 上游步骤与输出字段都必须明确
        if (blank(binding.sourceStepId()) || blank(binding.sourcePath())) {
            // 拒绝使用整段上游自然语言结果
            errors.add("STEP_OUTPUT 缺少 sourceStepId 或 sourcePath：" + step.id());
            return;
        }
        // 清理来源步骤 ID
        var sourceStepId = binding.sourceStepId().strip();
        // 来源步骤必须存在
        var sourceStep = stepsById.get(sourceStepId);
        // 未知步骤不能提供输出
        if (sourceStep == null) {
            // 记录未知来源步骤
            errors.add("sourceStepId 不存在：" + sourceStepId);
            return;
        }
        // 上游步骤必须位于当前步骤的传递依赖闭包内
        if (!ancestors(step.id().strip(), stepsById, new HashSet<>()).contains(sourceStepId)) {
            // 阻止读取未来步骤或无关并行步骤
            errors.add("sourceStepId 不在依赖闭包：" + step.id() + " <- " + sourceStepId);
            return;
        }
        // 获取已授权的上游能力
        var sourceCapability = context.capabilities().require(sourceStep.capabilityKey().strip());
        // 解析上游能力的输出 Schema
        var outputSchema = schema(sourceCapability.descriptor().outputSchema(), sourceStepId, errors);
        // 查找指定的上游输出字段
        var sourceSchema = propertySchema(outputSchema, binding.sourcePath());
        // 上游 Schema 必须提供该字段
        if (sourceSchema == null) {
            // 记录不存在的来源字段
            errors.add("sourcePath 不存在：" + sourceStepId + "." + binding.sourcePath());
            return;
        }
        // 输入输出字段基础类型必须兼容
        if (!compatible(sourceSchema, targetSchema)) {
            // 记录字段类型冲突
            errors.add("STEP_OUTPUT 字段类型不兼容：" + sourceStepId + "." + binding.sourcePath() + " -> " + step.id() + "." + binding.targetPath());
        }
    }

    private void validateConstantBinding(PlanStep step, InputBinding binding, JsonNode targetSchema, List<String> errors) {
        // 固定值必须能表示目标字段的实际输入
        if (binding.constantValue() == null) {
            // 拒绝没有值的常量绑定
            errors.add("CONSTANT 缺少 constantValue：" + step.id() + "." + binding.targetPath());
            return;
        }
        // 将常量转换为 JSON 值以检查基础类型
        var value = this.objectMapper.valueToTree(binding.constantValue());
        // 常量实际类型必须与目标 Schema 兼容
        if (!matchesValue(value, targetSchema)) {
            // 记录常量类型错误
            errors.add("CONSTANT 字段类型不兼容：" + step.id() + "." + binding.targetPath());
        }
    }

    private void validateRequired(PlanStep step, JsonNode inputSchema, Set<String> boundTargets, List<String> errors) {
        // 读取能力输入 Schema 中的必填字段
        var required = inputSchema.path("required");
        // 没有必填字段时无需检查
        if (!required.isArray()) {
            // 结束本次必填校验
            return;
        }
        // 逐个检查必填字段是否有明确来源
        for (var field : required) {
            // 未绑定字段不能构造合法能力输入
            if (!boundTargets.contains(field.asText())) {
                // 记录缺少输入来源的必填字段
                errors.add("必填字段缺少 inputBinding：" + step.id() + "." + field.asText());
            }
        }
    }

    private Set<String> ancestors(String stepId, Map<String, PlanStep> stepsById, Set<String> visited) {
        // 遍历当前步骤的直接依赖
        for (var dependency : stepsById.get(stepId).dependsOn()) {
            // 清理依赖 ID
            var dependencyId = dependency.strip();
            // 已访问依赖无需再次遍历
            if (!visited.add(dependencyId)) {
                // 继续检查其他依赖
                continue;
            }
            // 继续加入上游步骤的传递依赖
            ancestors(dependencyId, stepsById, visited);
        }
        // 返回完整的传递依赖集合
        return visited;
    }

    private JsonNode schema(String json, String stepId, List<String> errors) {
        try {
            // 解析注册能力提供的 JSON Schema
            return this.objectMapper.readTree(json);
        } catch (Exception exception) {
            // 记录无法用于字段绑定的 Schema
            errors.add("Capability Schema 无效：" + stepId);
            // 返回空对象避免后续空指针
            return this.objectMapper.createObjectNode();
        }
    }

    private JsonNode propertySchema(JsonNode schema, String path) {
        // 缺少路径或 Schema 时无法定位字段
        if (schema == null || blank(path)) {
            // 返回不存在的字段
            return null;
        }
        // 从对象 Schema 根节点开始查找
        var current = schema;
        // 按点分隔的字段路径逐层定位
        for (var segment : path.strip().split("\\.", -1)) {
            // 空路径段或未声明字段均视为不存在
            if (segment.isBlank() || !current.path("properties").has(segment)) {
                // 返回不存在的字段
                return null;
            }
            // 进入下一层字段 Schema
            current = current.path("properties").get(segment);
        }
        // 返回实际声明的字段 Schema
        return current;
    }

    private JsonNode valueAtPath(JsonNode node, String path) {
        // 从用户输入根节点开始查找
        var current = node;
        // 按点分隔的字段路径逐层读取
        for (var segment : path.strip().split("\\.", -1)) {
            // 空路径段或缺少值均不能绑定
            if (segment.isBlank() || current == null || !current.has(segment)) {
                // 返回不存在的用户字段
                return null;
            }
            // 进入下一层用户字段
            current = current.get(segment);
        }
        // 返回具体用户字段值
        return current;
    }

    private boolean matchesValue(JsonNode value, JsonNode schema) {
        // 读取目标字段的基础类型
        var type = schema.path("type").asText();
        // 对没有明确类型的字段采用保守拒绝
        return switch (type) {
            // 文本只能绑定到文本字段
            case "string" -> value.isTextual();
            // 整数只能绑定到整数字段
            case "integer" -> value.isIntegralNumber();
            // 数值字段允许整数和小数
            case "number" -> value.isNumber();
            // 布尔字段只接受布尔值
            case "boolean" -> value.isBoolean();
            // 数组字段只接受数组值
            case "array" -> value.isArray();
            // 对象字段只接受对象值
            case "object" -> value.isObject();
            // 空值字段只接受 JSON null
            case "null" -> value.isNull();
            // 未知类型不能放行计划
            default -> false;
        };
    }

    private boolean compatible(JsonNode sourceSchema, JsonNode targetSchema) {
        // 读取上游输出字段类型
        var sourceType = sourceSchema.path("type").asText();
        // 读取下游输入字段类型
        var targetType = targetSchema.path("type").asText();
        // 未声明类型时无法证明安全绑定
        if (sourceType.isBlank() || targetType.isBlank()) {
            // 保守拒绝类型不明的绑定
            return false;
        }
        // 整数输出允许写入数值输入
        if ("integer".equals(sourceType) && "number".equals(targetType)) {
            // 返回兼容结果
            return true;
        }
        // 不同基础类型不能直接绑定
        if (!sourceType.equals(targetType)) {
            // 返回不兼容结果
            return false;
        }
        // 数组字段还需校验元素类型
        if ("array".equals(sourceType) && sourceSchema.has("items") && targetSchema.has("items")) {
            // 递归检查数组元素 Schema
            return compatible(sourceSchema.path("items"), targetSchema.path("items"));
        }
        // 相同基础类型可以绑定
        return true;
    }

    private boolean blank(String value) {
        // 判断标识或字段路径是否为空白
        return value == null || value.isBlank();
    }
}
