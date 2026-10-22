package com.tianji.aigc.skill;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.skill.model.SkillDefinition;
import com.tianji.aigc.skill.model.SkillReference;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析SKILL.md、skill.meta.yml和受限引用文件。
 */
@Component
public class SkillParser {

    static final int MAX_REFERENCE_CHARS = 3_000;
    static final int MAX_REFERENCE_CONTEXT_CHARS = 12_000;
    private static final int MAX_SKILL_FILE_CHARS = 64_000;
    private static final int MAX_META_FILE_CHARS = 16_000;
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile(
            "\\A---\\s*\\R(.*?)\\R---\\s*(?:\\R)?(.*)\\z",
            Pattern.DOTALL);

    private final Yaml yaml;

    public SkillParser() {
        var options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(MAX_SKILL_FILE_CHARS);
        // SafeConstructor禁止YAML通过类型标签实例化任意Java对象
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    public SkillDefinition parse(Resource skillResource) {
        if (skillResource == null || !skillResource.exists()) {
            throw new SkillParseException("SKILL.md不存在");
        }
        try {
            var skillText = readLimited(
                    skillResource, MAX_SKILL_FILE_CHARS, "SKILL.md");
            var matcher = FRONT_MATTER_PATTERN.matcher(skillText);
            if (!matcher.matches()) {
                throw new SkillParseException("SKILL.md缺少合法front matter："
                        + skillResource.getDescription());
            }

            var frontMatter = readYamlMap(matcher.group(1), "SKILL.md front matter");
            var id = requiredText(frontMatter.get("id"), "Skill id不能为空");
            var agentType = readAgentType(frontMatter.get("agent-type"), id);
            var description = requiredText(
                    frontMatter.get("description"), "Skill description不能为空：" + id);
            var instructions = requiredText(
                    matcher.group(2), "SKILL.md正文不能为空：" + id);

            var metaResource = skillResource.createRelative("skill.meta.yml");
            if (!metaResource.exists()) {
                throw new SkillParseException("缺少skill.meta.yml：" + id);
            }
            var meta = readYamlMap(
                    readLimited(metaResource, MAX_META_FILE_CHARS, "skill.meta.yml"),
                    "skill.meta.yml");
            var displayName = requiredText(
                    meta.get("displayName"), "Skill displayName不能为空：" + id);
            var knowledgeBaseKeys = readStringSet(meta.get("knowledge-base-keys"));
            var allowedTools = readStringSet(meta.get("allowed-tools"));
            var allowedMcpServers = readStringSet(meta.get("allowed-mcp-servers"));
            var reflectionEnabled = readBoolean(meta.get("reflection-enabled"), false);
            var defaultSkill = readBoolean(meta.get("default"), false);
            var references = readReferences(meta.get("references"), id);
            var referenceContext = loadReferences(skillResource, references, id);

            return new SkillDefinition(
                    id,
                    agentType,
                    description,
                    displayName,
                    instructions,
                    referenceContext,
                    knowledgeBaseKeys,
                    allowedTools,
                    allowedMcpServers,
                    reflectionEnabled,
                    defaultSkill,
                    references);
        } catch (IOException e) {
            throw new SkillParseException(
                    "读取Skill失败：" + skillResource.getDescription(), e);
        }
    }

    private String loadReferences(Resource skillResource,
                                  List<SkillReference> references,
                                  String skillId) throws IOException {
        var context = new StringBuilder();
        for (var reference : references) {
            if (!isSafeReferencePath(reference.ref())) {
                throw new SkillParseException(
                        "Skill引用路径不安全：" + skillId + " -> " + reference.ref());
            }
            var referenceResource = skillResource.createRelative(reference.ref());
            if (!referenceResource.exists()) {
                throw new SkillParseException(
                        "Skill引用文件不存在：" + skillId + " -> " + reference.ref());
            }
            var content = readLimited(
                    referenceResource, MAX_REFERENCE_CHARS, "Skill引用文件");
            appendWithinTotalLimit(context, reference.key(), content);
            if (context.length() >= MAX_REFERENCE_CONTEXT_CHARS) {
                break;
            }
        }
        return context.toString();
    }

    boolean isSafeReferencePath(String referencePath) {
        if (referencePath == null || referencePath.isBlank()
                || referencePath.indexOf('\0') >= 0
                || referencePath.contains("\\")) {
            return false;
        }
        try {
            var path = Path.of(referencePath);
            var normalized = path.normalize();
            return !path.isAbsolute()
                    && !normalized.startsWith("..")
                    && !normalized.toString().isBlank();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void appendWithinTotalLimit(StringBuilder context,
                                        String key,
                                        String content) {
        var heading = context.isEmpty() ? "[" + key + "]\n" : "\n\n[" + key + "]\n";
        var remaining = MAX_REFERENCE_CONTEXT_CHARS - context.length();
        if (remaining <= heading.length()) {
            return;
        }
        context.append(heading);
        remaining = MAX_REFERENCE_CONTEXT_CHARS - context.length();
        context.append(content, 0, Math.min(content.length(), remaining));
    }

    private String readLimited(Resource resource,
                               int maxChars,
                               String fileType) throws IOException {
        var content = resource.getContentAsString(StandardCharsets.UTF_8);
        if (content.length() > maxChars) {
            // 引用内容允许截断；主定义和元数据超限时拒绝加载
            if (maxChars == MAX_REFERENCE_CHARS) {
                return content.substring(0, maxChars);
            }
            throw new SkillParseException(fileType + "超过字符上限："
                    + resource.getDescription());
        }
        return content;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(String content, String sourceName) {
        try {
            var value = this.yaml.load(content);
            if (!(value instanceof Map<?, ?> map)) {
                throw new SkillParseException(sourceName + "必须是YAML对象");
            }
            return (Map<String, Object>) map;
        } catch (SkillParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SkillParseException(sourceName + "不是合法YAML", e);
        }
    }

    private AgentTypeEnum readAgentType(Object value, String skillId) {
        var agentTypeName = requiredText(
                value, "Skill agent-type不能为空：" + skillId);
        try {
            var agentType = AgentTypeEnum.valueOf(agentTypeName);
            if (agentType == AgentTypeEnum.ROUTE) {
                throw new SkillParseException("Skill不能绑定路由Agent：" + skillId);
            }
            return agentType;
        } catch (IllegalArgumentException e) {
            throw new SkillParseException(
                    "Skill agent-type不存在：" + skillId + " -> " + agentTypeName, e);
        }
    }

    private Set<String> readStringSet(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Iterable<?> values)) {
            throw new SkillParseException("Skill列表字段必须使用YAML数组");
        }
        var result = new LinkedHashSet<String>();
        for (var item : values) {
            result.add(requiredText(item, "Skill列表项不能为空"));
        }
        return result;
    }

    private List<SkillReference> readReferences(Object value, String skillId) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> values)) {
            throw new SkillParseException("Skill references必须使用YAML数组：" + skillId);
        }
        var references = new ArrayList<SkillReference>();
        for (var item : values) {
            if (!(item instanceof Map<?, ?> referenceMap)) {
                throw new SkillParseException("Skill reference必须是YAML对象：" + skillId);
            }
            references.add(new SkillReference(
                    requiredText(referenceMap.get("key"), "Skill reference key不能为空：" + skillId),
                    requiredText(referenceMap.get("ref"), "Skill reference ref不能为空：" + skillId),
                    readBoolean(referenceMap.get("shared"), false)));
        }
        return references;
    }

    private boolean readBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new SkillParseException("Skill布尔字段只能填写true或false");
    }

    private String requiredText(Object value, String message) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new SkillParseException(message);
        }
        return text.strip();
    }
}
