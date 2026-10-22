package com.tianji.aigc.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianji.aigc.vo.ChatEventVO;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 内部排查流水，每次请求只写一个JSONL文件，不参与业务判断。 */
@Component
@Slf4j
public class AiDebugTrace {
    // 只在工具上下文携带标签，不作为模型提示词
    public static final String MODEL_STAGE = "aiDebugStage";
    // 超长载荷明确截断，避免调试文件无限膨胀
    private static final int MAX_PAYLOAD_CHARS = 1_000_000;
    // 日志日期与本地业务时间一致
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 先清理完整Bearer值，避免通用字段匹配只删掉Bearer单词
    private static final Pattern BEARER_TEXT = Pattern.compile("(?i)(Bearer\\s+)[^\\s\\\"',;}]+");
    // 清理正文中常见的服务凭据
    private static final Pattern SECRET_TEXT = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+|sk-[A-Za-z0-9_-]{10,}|((?:api[_-]?key|access[_-]?key(?:[_-]?secret)?|password|mcp[_-]?token|authorization)[\\\"']?\\s*[=:]\\s*[\\\"']?)[^\\s\\\"',;}]+");
    // 固定是否记录，关闭时不创建文件
    private final boolean enabled;
    // 保存日志根目录，不由用户问题决定路径
    private final Path directory;
    // 序列化调试数据
    private final ObjectMapper mapper;
    // 复用模型已有的调用上下文
    private final ObservationRegistry registry;
    // 仅保存仍在执行的请求，结束后移除
    private final ConcurrentHashMap<String, Session> active = new ConcurrentHashMap<>();

    public AiDebugTrace(@Value("${tj.ai.debug-trace.enabled:false}") boolean enabled, @Value("${tj.ai.debug-trace.directory:logs/ai-debug}") String directory, ObjectMapper mapper, ObservationRegistry registry) {
        // 保存调试开关
        this.enabled = enabled;
        // 将固定目录解析成绝对路径
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        // 保存JSON转换器
        this.mapper = mapper;
        // 保存模型使用的观测注册表
        this.registry = registry;
    }

    public Flux<ChatEventVO> stream(String question, String sessionId, Supplier<Flux<ChatEventVO>> action) {
        // 关闭时完整保留原调用方式
        if (!this.enabled) {
            // 不创建额外请求上下文
            return action.get();
        }
        // 创建本次请求独享的文件与上下文
        var session = newSession();
        // 先保存原始问题，路由调用也写入同一文件
        var request = begin(session, "REQUEST", "chat", Map.of("question", question == null ? "" : question, "sessionId", sessionId == null ? "" : sessionId));
        // 保存前端实际收到的部分文本
        var visibleText = new StringBuffer();
        // 同步路由与请求组装期间传播调试上下文
        try (var ignored = session.observation.openScope()) {
            // 只调用一次原业务入口
            return action.get().doOnNext(event -> {
                // 文本增量仅在内存聚合，不逐Token写文件
                if (event.getEventType() == 1001 && event.getEventData() != null && visibleText.length() < MAX_PAYLOAD_CHARS) {
                    // 有界保存已发给前端的回答
                    var text = String.valueOf(event.getEventData());
                    // 保留异常断流前已经收到的部分
                    visibleText.append(text, 0, Math.min(text.length(), MAX_PAYLOAD_CHARS - visibleText.length()));
                }
                // 保留规划进度和来源参数，便于对照步骤
                if (event.getEventType() == 1003 || event.getEventType() == 1004) {
                    // 参数事件允许空载荷，不让调试引入空指针
                    var data = new LinkedHashMap<String, Object>();
                    // 保留事件类型
                    data.put("eventType", event.getEventType());
                    // 保留来源或进度
                    data.put("data", event.getEventData());
                    // 不重复保存每个文本增量
                    write(session, "EVENT", null, "CHAT", "event", data, null);
                }
            }).doOnError(error -> failure(session.id, "chat-stream", error)).doFinally(signal -> {
                // 正常、异常和取消都保存已输出文本
                end(request, Map.of("visibleText", visibleText.toString(), "signal", signal.name()), null);
                // 释放请求索引和观测上下文
                close(session);
            }).contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, session.observation));
        } catch (RuntimeException exception) {
            // 同步组装失败也保留原始错误
            end(request, null, exception);
            // 不保留失败请求的索引
            close(session);
            // 不改变原业务错误处理
            throw exception;
        }
    }

    public <T> T callRequest(String question, Supplier<T> action) {
        // 普通文本接口也遵循总开关
        if (!this.enabled) {
            // 关闭时直接调用原入口
            return action.get();
        }
        // 为同步请求创建独立文件
        var session = newSession();
        // 保存请求原始输入
        var call = begin(session, "REQUEST", "chat-text", question);
        // 同步调用期间传播模型上下文
        try (var ignored = session.observation.openScope()) {
            // 获取原业务结果
            var output = action.get();
            // 保存返回值
            end(call, output, null);
            // 保持原返回类型
            return output;
        } catch (RuntimeException exception) {
            // 保存错误但不吞掉业务异常
            end(call, null, exception);
            // 沿用原业务边界
            throw exception;
        } finally {
            // 无论成功失败都移除请求索引
            close(session);
        }
    }

    public String currentId() {
        // 从当前观测链查找所属请求
        var session = current();
        // 没有调试请求时不提供替代ID
        return session == null ? null : session.id;
    }

    public boolean enabled() {
        // 调用方可在关闭时跳过额外包装
        return this.enabled;
    }

    public Observation currentObservation() {
        // 仅在开启调试时传播观测上下文
        return this.enabled ? this.registry.getCurrentObservation() : null;
    }

    public Observation.Scope scope(String requestId) {
        // 无请求标识时不访问活动索引
        if (requestId == null) {
            // 保持原线程上下文
            return null;
        }
        // 通过显式请求ID恢复线程池中的上下文
        var session = this.active.get(requestId);
        // 缺少调试记录时不影响原线程
        return session == null ? null : session.observation.openScope();
    }

    public Observation.Scope scope(Observation observation) {
        // 保留原观测父子关系，不使用全局Reactor Hook
        return observation == null ? null : observation.openScope();
    }

    public Call begin(String kind, String name, Object input) {
        // 当前调用与所属请求共用文件
        return begin(current(), kind, name, input);
    }

    public Call begin(Session session, String kind, String name, Object input) {
        // 关闭或缺少请求时不写文件
        if (session == null || !this.enabled) {
            // 调用方可以直接传给end
            return null;
        }
        // 生成配对输入输出的独立调用ID
        var call = new Call(session, UUID.randomUUID().toString().replace("-", ""), kind, name, System.nanoTime());
        // 调用前立即保存输入，进程中断时也有线索
        write(session, "INPUT", call, kind, name, input, null);
        // 返回本次调用句柄
        return call;
    }

    public void end(Call call, Object output, Throwable error) {
        // 同一调用只记录一次结束，避免取消与完成重复
        if (call == null || !call.finished.compareAndSet(false, true)) {
            // 关闭开关时同样直接返回
            return;
        }
        // 错误结果与正常输出使用同一调用ID
        write(call.session, error == null ? "OUTPUT" : "ERROR", call, call.kind, call.name, output, error);
    }

    public void failure(String requestId, String stage, Throwable error) {
        // 关闭时或没有请求时不引入额外错误
        if (!this.enabled || requestId == null) {
            // 不为错误消息创建孤立文件
            return;
        }
        // 已结束的请求不重新创建文件
        var call = begin(this.active.get(requestId), "CHECK", stage, null);
        // 保存被业务边界捕获的错误，例如非法引用
        end(call, null, error);
    }

    public Session current() {
        // 沿当前观测链定位请求
        var observation = currentObservation();
        // 不依赖线程名称或用户问题关联文件
        return observation == null ? null : find(observation.getContextView());
    }

    public Session find(Observation.ContextView context) {
        // 限制遍历深度，避免错误上下文形成循环
        for (var depth = 0; context != null && depth < 100; depth++) {
            // 请求上下文保存轻量文件句柄
            Session session = context.get(Session.class);
            // 找到后立即返回
            if (session != null) {
                // 不重新分配文件
                return session;
            }
            // 继续查找上一层调用
            var parent = context.getParentObservation();
            // 最外层没有父节点时结束
            context = parent == null ? null : parent.getContextView();
        }
        // 不属于调试请求的调用交给独立模型记录
        return null;
    }

    public Session newSession() {
        // 关闭时禁止创建目录或观测
        if (!this.enabled) {
            // 调用方据此跳过调试
            return null;
        }
        // 使用服务端随机ID组成安全文件名
        var id = UUID.randomUUID().toString().replace("-", "");
        // 按日期放置请求文件，便于手工清理
        var file = this.directory.resolve(LocalDate.now(ZONE).toString()).resolve(id + ".jsonl");
        // 创建请求独享的序号与写锁
        var session = new Session(id, file);
        // 在调用观测链中携带文件句柄
        session.observation = Observation.start("ai.debug.request", () -> new Observation.Context().put(Session.class, session), this.registry);
        // 供显式执行上下文和跨线程任务查找
        this.active.put(id, session);
        // 只在服务日志提示文件位置，不打印问题和密钥
        log.info("AI内部调试流水：{}", file);
        // 返回独立请求上下文
        return session;
    }

    public void close(Session session) {
        // 独立模型和聊天请求共用关闭逻辑
        if (session != null) {
            // 释放活动索引，已有调用句柄仍可记录迟到结果
            this.active.remove(session.id, session);
            // 不持有SSE流或运行中的业务任务
            session.observation.stop();
        }
    }

    private void write(Session session, String phase, Call call, String kind, String name, Object value, Throwable error) {
        // 同一请求串行写行，多个请求互不阻塞
        synchronized (session) {
            try {
                // 固定每行字段顺序，便于直接阅读
                var row = new LinkedHashMap<String, Object>();
                // 保存请求内单调递增序号
                row.put("seq", session.sequence.incrementAndGet());
                // 保存本地时间
                row.put("time", OffsetDateTime.now(ZONE).toString());
                // 文件内也保留请求ID
                row.put("traceId", session.id);
                // 输入输出靠callId配对，不靠完成顺序猜测
                row.put("callId", call == null ? null : call.id);
                // 标明模型、工具、MCP或检索阶段
                row.put("kind", kind);
                // 保留步骤或调用名称
                row.put("name", name);
                // 标明输入、输出、错误或进度
                row.put("phase", phase);
                // 只在结束行测量耗时
                if (call != null && !phase.equals("INPUT")) {
                    // 先测量耗时，再写结束行
                    row.put("elapsedMillis", Math.max(0L, (System.nanoTime() - call.startedAt) / 1_000_000L));
                }
                // 序列化之前不调用任意对象的toString
                var payload = this.mapper.valueToTree(value);
                // 普通文本载荷也要清理凭据
                if (payload != null && payload.isTextual()) {
                    // 保留文本类型与脱敏后的内容
                    payload = this.mapper.getNodeFactory().textNode(cleanText(payload.asText()));
                }
                // 清理结构化字段与正文中的凭据
                redact(payload);
                // 超长记录只截断载荷，整行仍是合法JSON
                var json = this.mapper.writeValueAsString(payload);
                // 明确标注裁剪，避免误以为完整结果
                row.put("truncated", json.length() > MAX_PAYLOAD_CHARS);
                // 正常情况下保留可展开的JSON对象
                row.put("data", json.length() > MAX_PAYLOAD_CHARS ? json.substring(0, MAX_PAYLOAD_CHARS) : payload);
                // 错误只保留类型与脱敏说明，不写完整堆栈
                if (error != null) {
                    // 保存异常类型
                    row.put("errorType", error.getClass().getSimpleName());
                    // 保留非法引用等直接排查线索
                    row.put("error", cleanText(String.valueOf(error.getMessage())));
                }
                // 需要记录时才创建日期目录
                Files.createDirectories(session.file.getParent());
                // 每次调用立即追加一行，不依赖请求最终成功
                Files.writeString(session.file, this.mapper.writeValueAsString(row) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception exception) {
                // 调试失败不能改变业务返回值
                log.warn("AI调试文件写入失败，file={}，errorType={}", session.file, exception.getClass().getSimpleName());
            }
        }
    }

    private void redact(JsonNode node) {
        // 空节点不需要处理
        if (node == null) {
            // 不生成额外内容
            return;
        }
        // 逐字段清理对象
        if (node instanceof ObjectNode object) {
            // 先收集字段名，避免修改时影响迭代
            var names = new java.util.ArrayList<String>();
            // 保留原字段顺序
            object.fieldNames().forEachRemaining(names::add);
            // 不保存凭据与供应商推理内容
            for (var name : names) {
                // 统一不同命名风格
                var key = name.replace("-", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
                // 仅匹配敏感字段，不误删totalTokens等计量
                if (java.util.Set.of("apikey", "accesskey", "accesskeyid", "accesskeysecret", "secretkey", "password", "authorization", "token", "accesstoken", "mcptoken", "reasoningcontent").contains(key)) {
                    // 敏感值不落盘
                    object.put(name, "[REDACTED]");
                } else if (object.get(name).isTextual()) {
                    // 正文也可能包含凭据
                    object.put(name, cleanText(object.get(name).asText()));
                } else {
                    // 递归清理嵌套字段
                    redact(object.get(name));
                }
            }
        } else if (node.isArray()) {
            // 递归清理消息与结果列表
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            // 同时清理字符串数组中的凭据
            for (var index = 0; index < array.size(); index++) {
                // 读取当前载荷
                var child = array.get(index);
                // 文本节点不能原地修改
                if (child.isTextual()) {
                    // 使用脱敏文本替换当前项
                    array.set(index, this.mapper.getNodeFactory().textNode(cleanText(child.asText())));
                } else {
                    // 继续清理对象和嵌套数组
                    redact(child);
                }
            }
        }
    }

    private String cleanText(String text) {
        // 认证头中的实际值先单独移除
        var cleaned = BEARER_TEXT.matcher(text).replaceAll("$1[REDACTED]");
        // 保留凭据名称，但移除实际值
        return SECRET_TEXT.matcher(cleaned).replaceAll(match -> (match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : "") + "[REDACTED]");
    }

    public static final class Session {
        // 请求与文件共用的随机标识
        public final String id;
        // 固定文件路径
        private final Path file;
        // 为并行调用生成阅读序号
        private final AtomicInteger sequence = new AtomicInteger();
        // 在同步和异步调用之间关联文件
        private Observation observation;

        private Session(String id, Path file) {
            // 固定请求ID
            this.id = id;
            // 固定安全路径
            this.file = file;
        }
    }

    public static final class Call {
        // 所属请求的文件句柄
        private final Session session;
        // 当前调用的配对ID
        private final String id;
        // 调用类型
        private final String kind;
        // 调用名称
        private final String name;
        // 单调时钟开始时间
        private final long startedAt;
        // 防止重复保存结束行
        private final AtomicBoolean finished = new AtomicBoolean();

        private Call(Session session, String id, String kind, String name, long startedAt) {
            // 保存请求文件
            this.session = session;
            // 保存配对ID
            this.id = id;
            // 保存调用类型
            this.kind = kind;
            // 保存调用名称
            this.name = name;
            // 保存开始时刻
            this.startedAt = startedAt;
        }
    }
}
