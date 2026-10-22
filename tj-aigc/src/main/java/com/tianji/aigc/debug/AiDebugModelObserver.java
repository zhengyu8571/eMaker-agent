package com.tianji.aigc.debug;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.observation.ModelObservationContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;

/** 在模型原始结果被JSON转换或引用校验处理之前保存调试记录。 */
@Slf4j
public class AiDebugModelObserver implements ObservationHandler<Observation.Context> {
    // 保存调用句柄的上下文键
    private static final String CALL = AiDebugModelObserver.class.getName() + ".call";
    // 标识不属于聊天入口的独立模型调用
    private static final String DETACHED = AiDebugModelObserver.class.getName() + ".detached";
    // 只负责文件记录，不改变模型结果
    private final AiDebugTrace trace;

    public AiDebugModelObserver(AiDebugTrace trace) {
        // 保存内部调试记录器
        this.trace = trace;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        // 保留客户端和Advisor的父子链，只对模型实际写行
        return this.trace.enabled();
    }

    @Override
    public void onStart(Observation.Context context) {
        // 调试序列化错误不能阻断模型
        try {
            // 只处理模型输入
            startModel(context);
        } catch (RuntimeException exception) {
            // 不打印可能包含凭据的异常内容
            log.warn("AI模型输入调试记录失败，errorType={}", exception.getClass().getSimpleName());
        }
    }

    private void startModel(Observation.Context context) {
        // 根上下文仅用于传播，不重复生成调用行
        if (!(context instanceof ModelObservationContext<?, ?> model)) {
            // 跳过非模型事件
            return;
        }
        // 模型可能通过Reactor或线程池继承请求
        var session = this.trace.find(context);
        // 后台模型调用没有聊天请求时单独保存
        if (session == null) {
            // 关闭开关时返回空，不生成文件
            session = this.trace.newSession();
            // 关闭开关时不修改模型上下文
            if (session == null) {
                // 不创建独立文件
                return;
            }
            // 在当前模型上下文中保留独立文件句柄
            context.put(AiDebugTrace.Session.class, session);
            // 结束时清理独立请求索引
            context.put(DETACHED, true);
        }
        // Chat请求只保存消息、参数和工具定义，不序列化运行对象
        var input = model.getRequest() instanceof Prompt prompt ? chatInput(prompt) : model.getRequest();
        // 优先使用客户端明确声明的调用阶段
        var name = model.getRequest() instanceof Prompt prompt && prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null ? String.valueOf(options.getToolContext().getOrDefault(AiDebugTrace.MODEL_STAGE, context.getName())) : model.getRequest() instanceof org.springframework.ai.embedding.EmbeddingRequest ? "EMBEDDING" : context.getName();
        // 保存实际模型入口的输入，包含RAG和记忆处理后的消息
        context.put(CALL, this.trace.begin(session, "MODEL", name, input));
    }

    @Override
    public void onStop(Observation.Context context) {
        // SDK结果无法序列化时仍保持业务原结果
        try {
            // 保存模型结束结果
            stopModel(context);
        } catch (RuntimeException exception) {
            // 调试错误只记录异常类型
            log.warn("AI模型输出调试记录失败，errorType={}", exception.getClass().getSimpleName());
        } finally {
            // 即使SDK结果读取失败也不遗留独立调用
            if (Boolean.TRUE.equals(context.get(DETACHED))) {
                // 清理后台模型调用的文件索引
                this.trace.close(context.get(AiDebugTrace.Session.class));
            }
        }
    }

    private void stopModel(Observation.Context context) {
        // 仅在模型停止时保存一次SDK结果
        if (context instanceof ModelObservationContext<?, ?> model) {
            // 不保存供应商返回的推理内容
            var output = model.getResponse() instanceof ChatResponse response ? chatOutput(response) : model.getResponse();
            // 原文在后续解析失败和引用校验拒绝前已经落盘
            this.trace.end(context.get(CALL), output, context.getError());
        }
    }

    private Object chatInput(Prompt prompt) {
        // 固定字段顺序，便于按消息角色阅读
        var input = new LinkedHashMap<String, Object>();
        // 保存真正进入模型的系统、历史、用户和工具消息
        input.put("messages", prompt.getInstructions().stream().map(message -> {
            // 单条消息不用toString，避免隐藏或多余字段
            var item = new LinkedHashMap<String, Object>();
            // 保留消息角色
            item.put("role", message.getMessageType().name());
            // 保留实际正文
            item.put("text", message.getText());
            // 下一轮消息中的工具调用参数也保留下来
            if (message instanceof AssistantMessage assistant) {
                // 不保存reasoningContent元数据
                item.put("toolCalls", assistant.getToolCalls());
            }
            // 保存反馈给模型的工具响应
            if (message instanceof ToolResponseMessage tool) {
                // 保留工具ID、名称和原始业务回包
                item.put("toolResponses", tool.getResponses());
            }
            // 返回可直接查看的消息对象
            return item;
        }).toList());
        // 只保存生成参数，不序列化供应商HTTP配置与运行对象
        if (prompt.getOptions() != null) {
            // 可空参数使用普通Map
            var options = new LinkedHashMap<String, Object>();
            // 保留模型名称
            options.put("model", prompt.getOptions().getModel());
            // 保留随机性参数
            options.put("temperature", prompt.getOptions().getTemperature());
            // 保留输出Token上限
            options.put("maxTokens", prompt.getOptions().getMaxTokens());
            // 保留采样参数
            options.put("topP", prompt.getOptions().getTopP());
            // 保留TopK参数
            options.put("topK", prompt.getOptions().getTopK());
            // 保留停止序列
            options.put("stopSequences", prompt.getOptions().getStopSequences());
            // 保存非凭据参数
            input.put("options", options);
        }
        // 工具实现对象不进入日志，只保存模型看到的定义
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            // 保留工具名称、说明和输入Schema
            input.put("tools", options.getToolCallbacks() == null ? java.util.List.of() : options.getToolCallbacks().stream().map(callback -> callback.getToolDefinition()).toList());
        }
        // 返回实际发送消息的调试快照
        return input;
    }

    private Object chatOutput(ChatResponse response) {
        // 保存模型原文与结束原因，而不是转换后的计划对象
        var output = new LinkedHashMap<String, Object>();
        // 多个候选结果保持原顺序
        output.put("results", response.getResults().stream().map(generation -> {
            // 不序列化包含供应商推理的消息元数据
            var item = new LinkedHashMap<String, Object>();
            // 保存生成文本
            item.put("text", generation.getOutput().getText());
            // 保存模型要求调用的工具
            item.put("toolCalls", generation.getOutput().getToolCalls());
            // 区分正常停止与工具调用
            item.put("finishReason", generation.getMetadata().getFinishReason());
            // 返回公开输出快照
            return item;
        }).toList());
        // 保留供应商实际提供的Token用量，不自行伪造
        output.put("usage", response.getMetadata().getUsage());
        // 保留供应商响应ID供排查
        output.put("responseId", response.getMetadata().getId());
        // SDK流式聚合语义保留，不能当成HTTP逐帧原始包
        output.put("outputScope", "SDK_RESPONSE");
        // 返回最终模型结果
        return output;
    }
}
