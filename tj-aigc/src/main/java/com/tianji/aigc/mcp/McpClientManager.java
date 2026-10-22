package com.tianji.aigc.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.McpClientProperties;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 懒连接、缓存和释放课程MCP客户端，启动时不访问远端。 */
@Component
public class McpClientManager {
    // 首版只接入已声明契约的课程查询
    private static final Set<String> COURSE_TOOLS = Set.of("searchCourseCandidate", "queryCourseById");
    // 固定启动时读取的连接配置
    private final Map<String, Slot> slots;
    // 初始化超时
    private final Duration initializationTimeout;
    // 工具请求超时
    private final Duration requestTimeout;
    // 首次失败冷却
    private final Duration initialBackoff;
    // 最大失败冷却
    private final Duration maxBackoff;
    // 初始化独立于Runtime线程池，避免SDK等待端点超出配置时间
    private final ThreadPoolExecutor initializationExecutor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
        // 初始化线程不阻止应用退出
        var thread = new Thread(task, "mcp-initialize");
        // 关闭应用后不保留后台握手
        thread.setDaemon(true);
        // 返回专用线程
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    // 禁止关闭后建立新连接
    private volatile boolean closed;
    // 内部排查时保留协议回包，不记录连接Token
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    // 配置快照与客户端生命周期由同一组件维护
    public McpClientManager(McpClientProperties properties) {
        // 校验初始化时间
        this.initializationTimeout = positive(properties.getInitializationTimeout());
        // 校验工具请求时间
        this.requestTimeout = positive(properties.getRequestTimeout());
        // 校验初始冷却时间
        this.initialBackoff = positive(properties.getInitialBackoff());
        // 校验最大冷却时间
        this.maxBackoff = positive(properties.getMaxBackoff());
        // 防止退避上限小于初始值
        if (this.maxBackoff.compareTo(this.initialBackoff) < 0) {
            // 拒绝错误的退避配置
            throw new IllegalArgumentException("MCP最大退避时间不能小于初始退避时间");
        }
        // 暂存有效服务
        var configured = new LinkedHashMap<String, Slot>();
        // 仅在客户端开启时注册服务
        if (properties.isEnabled()) {
            // 逐项读取服务配置
            properties.getServers().forEach((id, server) -> {
                // 忽略明确禁用的服务
                if (server.isEnabled()) {
                    // 校验配置后保存不可变快照
                    configured.put(validId(id), slot(server));
                }
            });
        }
        // 固定服务目录供CapabilityProvider读取
        this.slots = Map.copyOf(configured);
    }

    // 返回每个服务允许注册的工具
    public Map<String, Set<String>> configuredTools() {
        // 不向调用方暴露Token和URL
        var tools = new LinkedHashMap<String, Set<String>>();
        // 复制服务的工具白名单
        this.slots.forEach((id, slot) -> tools.put(id, slot.tools));
        // 返回只读目录
        return Map.copyOf(tools);
    }

    // 外层Runtime覆盖连接与一次工具请求的总等待
    public Duration invocationTimeout() {
        // 留出初始化、发现和工具请求的等待额度
        return this.initializationTimeout.plus(this.requestTimeout.multipliedBy(2));
    }

    // 实际调用前完成工具发现和输入契约检查
    public McpSchema.CallToolResult call(String serverId, String toolName, Map<String, Object> input, String expectedSchema, ObjectMapper mapper) {
        // 原始协议回包与能力解包后的结果分开记录
        var call = this.debugTrace == null ? null : this.debugTrace.begin("MCP_PROTOCOL", serverId + "/" + toolName, input);
        try {
            // 沿用工具发现、白名单和Schema核验
            var result = callInternal(serverId, toolName, input, expectedSchema, mapper);
            // 即使isError=true也保留远端原始说明
            if (this.debugTrace != null) {
                // 不把协议认证头或客户端对象写入文件
                this.debugTrace.end(call, result, null);
            }
            // 返回原SDK结果，由能力适配器继续处理
            return result;
        } catch (RuntimeException exception) {
            // 网络或初始化失败同样保留本次参数
            if (this.debugTrace != null) {
                // 原稳定异常仍交给Runtime映射
                this.debugTrace.end(call, null, exception);
            }
            // 不改变失败与冷却策略
            throw exception;
        }
    }

    private McpSchema.CallToolResult callInternal(String serverId, String toolName, Map<String, Object> input, String expectedSchema, ObjectMapper mapper) {
        // 校验服务来自启动配置
        var slot = this.slots.get(serverId);
        // 防止代码绕过配置白名单调用远端工具
        if (slot == null || !slot.tools.contains(toolName)) {
            // 不尝试访问未授权服务
            throw new IllegalArgumentException("MCP服务或工具未配置");
        }
        // 懒加载已验证的客户端
        var connection = connection(slot);
        try {
            // 核对远端已发现的工具输入Schema
            var remote = connection.tools.get(toolName);
            // SDK的JsonSchema不保留根级$schema标识
            var expected = mapper.readTree(expectedSchema);
            // 仅忽略传输层丢弃的规范标识，仍核对所有参数约束
            if (expected instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                // 本地生成器固定使用同一JSON Schema规范
                object.remove("$schema");
            }
            // 使用JSON结构比较，忽略字段顺序
            if (remote == null || !expected.equals(mapper.valueToTree(remote.inputSchema()))) {
                // 远端契约漂移时禁止执行
                throw new IllegalStateException("MCP课程工具输入Schema与本地契约不一致");
            }
            // 通过官方SDK发送工具调用
            return connection.client.callTool(new McpSchema.CallToolRequest(toolName, input));
        } catch (RuntimeException exception) {
            // 对外保持稳定错误，内部流水保留原始原因
            if (this.debugTrace != null) {
                // Schema漂移、超时和网络错误可在文件中区分
                this.debugTrace.failure(this.debugTrace.currentId(), "MCP_CALL", exception);
            }
            // 传输或契约异常后清理旧连接
            invalidate(slot, connection);
            // 只保留稳定错误，避免远端异常暴露配置
            throw new IllegalStateException("MCP课程调用失败，未切换为本地工具");
        } catch (java.io.IOException exception) {
            // 无法解析本地Schema属于配置错误
            throw new IllegalStateException("MCP课程输入Schema无法解析");
        }
    }

    // 同一服务只允许一个线程执行初始化
    private Connection connection(Slot slot) {
        // 锁仅覆盖初始化，不串行化所有工具请求
        synchronized (slot) {
            // 关闭后的管理器不允许重连
            if (this.closed) {
                // 返回生命周期错误
                throw new IllegalStateException("MCP客户端已经关闭");
            }
            // 已建立连接直接复用
            if (slot.connection != null) {
                // 返回固定工具目录的客户端
                return slot.connection;
            }
            // 失败冷却期内快速返回
            if (System.nanoTime() < slot.retryAfter) {
                // 避免并发请求持续撞击离线服务
                throw new IllegalStateException("MCP服务暂不可用，处于重连冷却期");
            }
            // GET和POST使用同一认证请求模板
            var transport = HttpClientSseClientTransport.builder(slot.url).sseEndpoint(slot.sseEndpoint).clientBuilder(HttpClient.newBuilder().connectTimeout(this.initializationTimeout)).customizeRequest(request -> request.setHeader("Authorization", "Bearer " + slot.token)).build();
            // SDK同时限制握手和业务请求
            var client = McpClient.sync(transport).initializationTimeout(this.initializationTimeout).requestTimeout(this.requestTimeout).build();
            // 保存可取消的初始化任务
            java.util.concurrent.Future<Connection> initialization = null;
            try {
                // 握手与工具发现都在有界专用池中执行
                initialization = this.initializationExecutor.submit(() -> initializeAndDiscover(client, slot));
                // 队列等待也计入初始化与发现总时间
                slot.connection = initialization.get(this.initializationTimeout.plus(this.requestTimeout).toNanos(), TimeUnit.NANOSECONDS);
                // 连接恢复后重置退避次数
                slot.failures = 0;
                // 返回已完整初始化的连接
                return slot.connection;
            } catch (Exception exception) {
                // 初始化错误先保留，再执行原连接清理
                if (this.debugTrace != null) {
                    // 不把服务URL或Token作为调试载荷
                    this.debugTrace.failure(this.debugTrace.currentId(), "MCP_INITIALIZE", exception.getCause() == null ? exception : exception.getCause());
                }
                // 超时或取消后不能让迟到初始化更新缓存
                if (initialization != null) {
                    // 尝试中断SDK内部端点等待
                    initialization.cancel(true);
                }
                // 保留Runtime传入的中断信号
                if (exception instanceof InterruptedException) {
                    // 上层仍可识别主动取消
                    Thread.currentThread().interrupt();
                }
                // 释放失败握手的SSE连接
                client.close();
                // 后续请求按有界指数退避重连
                backoff(slot);
                // 不向上层泄露地址、Token或远端错误
                throw new IllegalStateException("MCP服务初始化或工具发现失败");
            }
        }
    }

    // 握手与分页发现共享外层初始化截止时间
    private Connection initializeAndDiscover(McpSyncClient client, Slot slot) {
        // 必须完成initialize后才能读取工具
        client.initialize();
        // 保存已发现的工具
        var discovered = new LinkedHashMap<String, McpSchema.Tool>();
        // 首次请求不携带分页游标
        String cursor = null;
        // 页数上限避免恶意无限工具目录
        for (var page = 0; page < 10; page++) {
            // 获取一页工具
            var listed = client.listTools(cursor);
            // 拒绝覆盖相同工具名称
            for (var tool : listed.tools()) {
                // 校验目录内工具名唯一
                if (discovered.putIfAbsent(tool.name(), tool) != null) {
                    // 远端重名工具不能用于规划
                    throw new IllegalStateException("MCP工具目录包含重名工具");
                }
            }
            // 已找到全部允许工具即可停止分页
            if (discovered.keySet().containsAll(slot.tools)) {
                // 返回固定工具目录和客户端
                return new Connection(client, Map.copyOf(discovered));
            }
            // 更新下一页游标
            cursor = listed.nextCursor();
            // 最后一页仍缺少工具则结束发现
            if (cursor == null || cursor.isBlank()) {
                // 交给外层初始化失败处理
                break;
            }
        }
        // 缺少配置要求的工具不能半成功启动
        throw new IllegalStateException("MCP服务缺少配置的课程工具");
    }

    // 仅失效本次调用使用的连接
    private void invalidate(Slot slot, Connection connection) {
        // 防止旧请求删除已重建的连接
        synchronized (slot) {
            // 只有仍在缓存中的连接需要释放
            if (slot.connection == connection) {
                // 清除缓存后允许冷却期结束再连接
                slot.connection = null;
                // 立即释放SDK会话
                connection.client.close();
                // 记录新的重连冷却期
                backoff(slot);
            }
        }
    }

    // 失败后的退避只控制下次连接，不自动重放工具
    private void backoff(Slot slot) {
        // 限制指数避免移位溢出
        var exponent = Math.min(slot.failures++, 10);
        // 冷却时间受配置上限约束
        var delay = Math.min(this.maxBackoff.toNanos(), this.initialBackoff.toNanos() * (1L << exponent));
        // 使用单调时钟记录可重连时刻
        slot.retryAfter = System.nanoTime() + delay;
    }

    // 应用关闭时释放全部已建立连接
    @PreDestroy
    public void close() {
        // 先禁止并发请求建立新连接
        this.closed = true;
        // 取消尚未完成的握手任务
        this.initializationExecutor.shutdownNow();
        // 逐个释放服务连接
        this.slots.values().forEach(slot -> {
            // 与初始化线程使用同一把锁
            synchronized (slot) {
                // 仅关闭实际建立过的客户端
                if (slot.connection != null) {
                    // 释放SDK客户端和SSE连接
                    slot.connection.client.close();
                    // 防止重复关闭已释放的连接
                    slot.connection = null;
                }
            }
        });
    }

    // 将外部配置转换为内部固定快照
    private Slot slot(McpClientProperties.Server server) {
        // 只允许已知只读课程工具
        if (server.getAllowedTools() == null || server.getAllowedTools().isEmpty() || !COURSE_TOOLS.containsAll(server.getAllowedTools())) {
            // 首版不接受未知工具和写工具
            throw new IllegalArgumentException("MCP仅支持声明的只读课程工具");
        }
        // 校验服务Token存在
        if (server.getToken() == null || server.getToken().isBlank()) {
            // Token不进入异常文本
            throw new IllegalArgumentException("MCP客户端服务Token不能为空");
        }
        // 检查连接地址为HTTP源地址
        var uri = URI.create(server.getUrl());
        // SDK通过源地址解析握手返回路径
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            // 避免凭据嵌入URL或错误路径拼接
            throw new IllegalArgumentException("MCP服务URL必须是HTTP源地址");
        }
        // SSE端点必须为相对源地址的绝对路径
        if (server.getSseEndpoint() == null || !server.getSseEndpoint().startsWith("/") || server.getSseEndpoint().startsWith("//") || server.getSseEndpoint().contains("?") || server.getSseEndpoint().contains("#")) {
            // 拒绝不稳定的握手路径
            throw new IllegalArgumentException("MCP SSE端点必须是绝对路径");
        }
        // 保存固定配置，后续改配置需重启应用
        return new Slot(server.getUrl(), server.getSseEndpoint(), server.getToken(), Set.copyOf(server.getAllowedTools()));
    }

    // 约束服务ID可安全组成Capability key
    private static String validId(String id) {
        // 仅接受小写字母、数字和连字符
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
            // 拒绝歧义服务ID
            throw new IllegalArgumentException("MCP服务ID格式不合法");
        }
        // 返回已验证ID
        return id;
    }

    // 超时和退避都必须有正数上限
    private static Duration positive(Duration value) {
        // 限制等待区间，防止纳秒换算溢出
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(5)) > 0) {
            // 返回稳定配置错误
            throw new IllegalArgumentException("MCP时间配置必须大于0且不超过5分钟");
        }
        // 返回已验证时间
        return value;
    }

    // 服务连接槽不提供包含Token的toString
    private static final class Slot {
        // HTTP源地址
        private final String url;
        // SSE路径
        private final String sseEndpoint;
        // 服务Token
        private final String token;
        // 允许发现的工具
        private final Set<String> tools;
        // 已握手连接
        private Connection connection;
        // 连续连接失败次数
        private int failures;
        // 下次允许重连的单调时间
        private long retryAfter;

        // 固定单个连接槽配置
        private Slot(String url, String sseEndpoint, String token, Set<String> tools) {
            // 保存HTTP源地址
            this.url = url;
            // 保存SSE路径
            this.sseEndpoint = sseEndpoint;
            // 保存服务Token
            this.token = token;
            // 保存工具白名单
            this.tools = tools;
        }
    }

    // 缓存官方客户端和发现的工具Schema
    private record Connection(McpSyncClient client, Map<String, McpSchema.Tool> tools) {
    }
}
