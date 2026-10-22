package com.tianji.aigc.skill;

import com.tianji.aigc.capability.CapabilityKeys;
import com.tianji.aigc.capability.CapabilityRegistry;
import com.tianji.aigc.capability.McpBackedCapability;
import com.tianji.aigc.capability.ToolBackedCapability;
import com.tianji.aigc.capability.enums.CapabilityKind;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.skill.model.ActivatedSkill;
import com.tianji.aigc.skill.model.SkillDefinition;
import com.tianji.aigc.skill.model.SkillDiagnostic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 启动时扫描、校验并固定全部可用 Skill。
 */
@Slf4j
@Component
public class SkillRegistry {

    // Skill 配置文件扫描路径
    private static final String SKILL_PATTERN = "classpath*:skills/*/SKILL.md";

    // 有效 Skill 定义
    private final Map<String, SkillDefinition> definitions;

    // 已激活的 Skill
    private final Map<String, ActivatedSkill> activatedSkills;

    // 每种 Agent 对应的默认 Skill
    private final Map<AgentTypeEnum, String> defaultSkillIds;

    // 被禁用 Skill 的诊断信息
    private final List<SkillDiagnostic> diagnostics;

    /**
     * Spring 使用的主构造器。
     * 存在多个构造器时必须显式标注，否则 Spring 无法推断要注入哪一个。
     */
    @Autowired
    public SkillRegistry(SkillParser parser, CapabilityRegistry capabilityRegistry, KnowledgeLibraryKeyValidator libraryKeyValidator) {
        // 扫描 Skill 文件后交给内部构造器处理
        this(parser, capabilityRegistry, libraryKeyValidator, scanResources());
    }

    /**
     * 实际执行 Skill 加载和校验。
     */
    SkillRegistry(SkillParser parser, CapabilityRegistry capabilityRegistry, KnowledgeLibraryKeyValidator libraryKeyValidator, List<Resource> resources) {

        // 保存成功解析的 Skill
        var loadedDefinitions = new ArrayList<LoadedSkill>();

        // 保存被禁用的 Skill
        var disabledSkills = new ArrayList<SkillDiagnostic>();

        // 遍历所有 Skill 配置文件
        resources.stream()
                // 按资源路径排序
                .sorted(Comparator.comparing(Resource::getDescription))
                // 逐个解析 Skill
                .forEach(resource -> parseResource(parser, resource, loadedDefinitions, disabledSkills));

        // 找出重复的 Skill ID
        var duplicateIds = loadedDefinitions.stream()
                // 按 Skill ID 分组并统计数量
                .collect(Collectors.groupingBy(loaded -> loaded.definition().id(), Collectors.counting()))
                // 转成 Entry 流
                .entrySet().stream()
                // 保留出现次数大于 1 的 ID
                .filter(entry -> entry.getValue() > 1)
                // 取出 Skill ID
                .map(Map.Entry::getKey)
                // 收集成 Set
                .collect(Collectors.toSet());

        // 保存有效 Skill 定义
        var validDefinitions = new LinkedHashMap<String, SkillDefinition>();

        // 保存有效的 ActivatedSkill
        var validActivatedSkills = new LinkedHashMap<String, ActivatedSkill>();

        // 保存每种 Agent 的默认 Skill
        var defaults = new EnumMap<AgentTypeEnum, String>(AgentTypeEnum.class);

        // 遍历所有成功解析的 Skill
        for (var loaded : loadedDefinitions) {

            // 获取 Skill 定义
            var definition = loaded.definition();

            // Skill ID 重复
            if (duplicateIds.contains(definition.id())) {

                // 禁用所有同名 Skill
                disable(disabledSkills, definition.id(), loaded.resource(), "Skill id重复，全部同名定义均已禁用");

                // 跳过当前 Skill
                continue;
            }

            try {

                // 校验并激活 Skill
                var activatedSkill = validateAndActivate(definition, capabilityRegistry, libraryKeyValidator);

                // 如果当前 Skill 是默认 Skill
                if (definition.defaultSkill()) {

                    // 尝试设置为当前 Agent 的默认 Skill
                    var existing = defaults.putIfAbsent(definition.agentType(), definition.id());

                    // 已存在默认 Skill，说明发生冲突
                    if (existing != null) {
                        throw new SkillParseException(
                                "Agent存在多个默认Skill：" + definition.agentType() + " -> " + existing + "、" + definition.id()
                        );
                    }
                }

                // 保存有效 Skill 定义
                validDefinitions.put(definition.id(), definition);

                // 保存激活后的 Skill
                validActivatedSkills.put(definition.id(), activatedSkill);

            } catch (RuntimeException e) {

                // 校验失败则禁用 Skill
                disable(disabledSkills, definition.id(), loaded.resource(), e.getMessage());
            }
        }

        // 固定 Skill 定义，禁止修改
        this.definitions = Collections.unmodifiableMap(validDefinitions);

        // 固定 ActivatedSkill
        this.activatedSkills = Collections.unmodifiableMap(validActivatedSkills);

        // 固定默认 Skill 映射
        this.defaultSkillIds = Collections.unmodifiableMap(defaults);

        // 固定诊断信息
        this.diagnostics = List.copyOf(disabledSkills);
    }

    /**
     * 根据 Skill ID 查找 Skill。
     */
    public Optional<ActivatedSkill> find(String skillId) {

        // ID 为空直接返回空
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }

        // 去掉首尾空格后查找
        return Optional.ofNullable(this.activatedSkills.get(skillId.strip()));
    }

    /**
     * 获取 Skill，不存在则抛异常。
     */
    public ActivatedSkill require(String skillId) {

        // Skill 不存在或被禁用时抛异常
        return find(skillId).orElseThrow(() ->
                new IllegalArgumentException("Skill不存在或已禁用：" + skillId));
    }

    /**
     * 获取某个 Agent 的默认 Skill。
     */
    public Optional<ActivatedSkill> defaultFor(AgentTypeEnum agentType) {

        // 先获取默认 Skill ID，再查找 Skill
        return Optional.ofNullable(this.defaultSkillIds.get(agentType))
                .flatMap(this::find);
    }

    /**
     * 获取最终应该使用的 Skill。
     */
    public ActivatedSkill resolve(String skillId, AgentTypeEnum targetAgentType) {

        // 查找指定 Skill，并检查是否属于目标 Agent
        var selected = find(skillId)
                .filter(skill -> skill.agentType() == targetAgentType);

        // 指定 Skill 无效时使用默认 Skill
        return selected.or(() -> defaultFor(targetAgentType))
                .orElseThrow(() ->
                        new IllegalStateException("Agent缺少可用默认Skill：" + targetAgentType));
    }

    /**
     * 构建 Skill 目录文本。
     */
    public String buildCatalog() {

        // 将所有 Skill 拼接成目录
        return this.definitions.values().stream()
                .map(definition -> "- " + definition.id()
                        + "（" + definition.agentType() + "）："
                        + definition.description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 获取 Skill 诊断信息。
     */
    public List<SkillDiagnostic> diagnostics() {

        // 返回被禁用 Skill 的信息
        return this.diagnostics;
    }

    /**
     * 获取所有有效 Skill 定义。
     */
    public List<SkillDefinition> definitions() {

        // 转成新的 List 返回
        return List.copyOf(this.definitions.values());
    }

    /**
     * 校验并激活 Skill。
     */
    private ActivatedSkill validateAndActivate(SkillDefinition definition, CapabilityRegistry capabilityRegistry, KnowledgeLibraryKeyValidator libraryKeyValidator) {

        // 校验 Skill 使用的知识库 Key
        var keyValidation = libraryKeyValidator.validate(definition.knowledgeBaseKeys());

        // 存在不存在的知识库 Key
        if (!keyValidation.missingKeys().isEmpty()) {
            throw new SkillParseException("知识库key不存在：" + keyValidation.missingKeys());
        }

        // 存在重复知识库 Key
        if (!keyValidation.duplicateKeys().isEmpty()) {
            throw new SkillParseException("知识库key重复：" + keyValidation.duplicateKeys());
        }

        // 保存 Skill 允许使用的 Capability
        var capabilityKeys = new LinkedHashSet<String>();

        // 根据 Agent 类型生成 Capability Key
        var agentKey = CapabilityKeys.agent(definition.agentType());

        // 检查 Agent Capability 是否存在
        capabilityRegistry.require(agentKey);

        // 加入允许调用的 Capability
        capabilityKeys.add(agentKey);

        // 如果是知识库 Agent
        if (definition.agentType() == AgentTypeEnum.KNOWLEDGE) {

            // 检查知识库 RAG Capability 是否存在
            capabilityRegistry.require(CapabilityKeys.KNOWLEDGE_RAG);

            // 加入 RAG Capability
            capabilityKeys.add(CapabilityKeys.KNOWLEDGE_RAG);
        }

        // 遍历 Skill 配置的本地工具
        for (var toolName : definition.allowedTools()) {

            // 有前缀则直接使用，否则补上本地工具前缀
            var capabilityKey = toolName.startsWith(CapabilityKeys.LOCAL_TOOL_PREFIX)
                    ? toolName
                    : CapabilityKeys.localTool(toolName);

            // 获取对应 Capability
            var capability = capabilityRegistry.require(capabilityKey);

            // 检查是否真的是本地工具
            if (capability.descriptor().kind() != CapabilityKind.LOCAL_TOOL) {
                throw new SkillParseException("allowed-tools不是本地工具：" + toolName);
            }

            // 加入允许调用的 Capability
            capabilityKeys.add(capabilityKey);
        }

        // 把已配置服务中的只读MCP工具加入当前Skill
        for (var serverId : definition.allowedMcpServers()) {
            // 仅按稳定服务ID选择已注册工具
            var mcpKeys = capabilityRegistry.descriptors().stream()
                    .filter(descriptor -> descriptor.kind() == CapabilityKind.MCP_TOOL)
                    .filter(descriptor -> capabilityRegistry.require(descriptor.key()) instanceof McpBackedCapability mcp && mcp.serverId().equals(serverId)).map(descriptor -> descriptor.key()).toList();
            // MCP关闭或未配置的服务不能产生可用Skill
            if (mcpKeys.isEmpty()) {
                // 本地默认Skill不受该配置错误影响
                throw new SkillParseException("Skill的MCP服务未启用或未注册：" + serverId);
            }
            // 固定本次Skill允许的远端能力
            capabilityKeys.addAll(mcpKeys);
        }

        // 同一Skill禁止同时暴露同名本地和远端工具
        var toolNames = new LinkedHashSet<String>();
        // 检查最终将暴露给模型的工具集合
        for (var key : capabilityKeys) {
            // 读取实际能力
            var capability = capabilityRegistry.require(key);
            // Agent和RAG不参与工具名冲突检查
            if (capability instanceof ToolBackedCapability tool && !toolNames.add(tool.toolCallback().getToolDefinition().name())) {
                // 提醒配置选择一个调用渠道
                throw new SkillParseException("Skill包含重复工具，请在本地和MCP之间选择一种：" + key);
            }
        }

        // 校验通过后生成 ActivatedSkill
        return new ActivatedSkill(
                definition.id(),                  // Skill ID
                definition.agentType(),           // 所属 Agent
                definition.instructions(),        // Skill 指令
                definition.referenceContext(),    // 参考上下文
                definition.knowledgeBaseKeys(),   // 知识库 Key
                capabilityKeys,                   // 可用 Capability
                definition.allowedMcpServers(),   // 可用 MCP Server
                definition.reflectionEnabled()    // 是否启用 Reflection
        );
    }

    /**
     * 解析单个 Skill 文件。
     */
    private void parseResource(SkillParser parser, Resource resource, List<LoadedSkill> loadedDefinitions, List<SkillDiagnostic> disabledSkills) {

        try {

            // 解析 Skill 并保存来源信息
            loadedDefinitions.add(new LoadedSkill(
                    parser.parse(resource),
                    resource.getDescription()
            ));

        } catch (RuntimeException e) {

            // 解析失败则记录为禁用
            disable(disabledSkills, null, resource.getDescription(), e.getMessage());
        }
    }

    /**
     * 记录被禁用的 Skill。
     */
    private void disable(List<SkillDiagnostic> disabledSkills, String skillId, String resource, String reason) {

        // 构造诊断信息
        var diagnostic = new SkillDiagnostic(
                skillId,
                resource,
                reason == null ? "未知配置错误" : reason
        );

        // 加入诊断列表
        disabledSkills.add(diagnostic);

        // 输出警告日志
        log.warn(
                "禁用Skill，id={}，resource={}，reason={}",
                diagnostic.skillId(),
                diagnostic.resource(),
                diagnostic.reason()
        );
    }

    /**
     * 扫描所有 Skill 配置文件。
     */
    private static List<Resource> scanResources() {

        try {

            // 创建 Spring 资源扫描器
            var resolver = new PathMatchingResourcePatternResolver();

            // 扫描所有 SKILL.md
            return List.of(resolver.getResources(SKILL_PATTERN));

        } catch (IOException e) {

            // 扫描失败时抛异常
            throw new IllegalStateException("扫描classpath Skill失败", e);
        }
    }

    /**
     * 保存 Skill 定义和来源文件。
     */
    private record LoadedSkill(SkillDefinition definition, String resource) {
    }
}
