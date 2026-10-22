package com.tianji.aigc.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.enums.MessageTypeEnum;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.memory.MyAssistantMessage;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.EnhancedMemoryService;
import com.tianji.aigc.vo.ChatSessionVO;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    private final SessionProperties sessionProperties;
    private final ChatMemory chatMemory;
    // 会话删除时清除独立摘要，用户偏好仍可跨会话保留
    @Autowired(required = false)
    private EnhancedMemoryService enhancedMemory;

    @Override
    public SessionVO createSession(Integer num) {
        var sessionVO = BeanUtil.toBean(this.sessionProperties, SessionVO.class);
        // 随机生成3个热门问题
        sessionVO.setExamples(RandomUtil.randomEleList(this.sessionProperties.getExamples(), num));

        //生成sessionId
        sessionVO.setSessionId(IdUtil.simpleUUID());

        //保存会话数据到数据库
        var chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser()) //当前用户id
                .build();
        super.save(chatSession);

        return sessionVO;
    }

    @Override
    public List<SessionVO.Example> hotExamples(Integer num) {
        return RandomUtil.randomEleList(this.sessionProperties.getExamples(), num);
    }

    @Override
    public List<MessageVO> queryBySessionId(String sessionId) {
        // 将sessionId转化为对话id
        var conversationId = ChatService.getConversationId(sessionId);

        // 查询对话列表
        List<Message> messageList = this.chatMemory.get(conversationId);

        // 转化为vo列表
        return StreamUtil.of(messageList)
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT || message.getMessageType() == MessageType.USER)
                .map(message -> {
                    if (message instanceof MyAssistantMessage myAssistantMessage) {
                        return MessageVO.builder()
                                .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                                .content(message.getText())
                                .params(myAssistantMessage.getParams())
                                .build();
                    }

                    return MessageVO.builder()
                            .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                            .content(message.getText())
                            .build();
                })
                .toList();
    }

    @Async // 异步更新数据
    @Override
    public void update(String sessionId, String title, Long userId) {
        // 获取会话列表
        var chatSessionList = super.lambdaQuery()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId)
                .list();

        if (CollUtil.isEmpty(chatSessionList)) {
            return;
        }

        // 获取第一个会话对象数据
        var chatSession = chatSessionList.get(0);

        if (StrUtil.isEmpty(chatSession.getTitle()) && StrUtil.isNotEmpty(title)) {
            // 更新会话标题，如果标题长度超过100个字符，则截取100个字符
            chatSession.setTitle(StrUtil.sub(title, 0, 100));
        }

        // 设置更新时间
        chatSession.setUpdateTime(LocalDateTime.now());

        // 更新数据
        super.updateById(chatSession);
    }

    @Override
    public Map<String, List<ChatSessionVO>> queryHistorySession() {
        var userId = UserContext.getUser();
        // 根据条件查询会话列表
        var chatSessionList = super.lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .isNotNull(ChatSession::getTitle)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 30")
                .list();

        if (CollUtil.isEmpty(chatSessionList)) {
            return Map.of();
        }

        // 转化为vo对象列表
        var chatSessionVOList = CollStreamUtil.toList(chatSessionList, chatSession -> ChatSessionVO.builder()
                .title(chatSession.getTitle())
                .updateTime(chatSession.getUpdateTime())
                .sessionId(chatSession.getSessionId())
                .build());

        // 根据时间差进行数据分组
        final var TODAY = "当天";
        final var LAST_30_DAYS = "最近30天";
        final var LAST_YEAR = "最近1年";
        final var MORE_THAN_YEAR = "1年以上";

        // 获取当前的日期
        var now = LocalDateTime.now().toLocalDate();

        return CollStreamUtil.groupByKey(chatSessionVOList, chatSessionVO -> {
            var days = Math.abs(ChronoUnit.DAYS.between(chatSessionVO.getUpdateTime().toLocalDate(), now));
            if (days == 0) {
                return TODAY;
            } else if (days <= 30) {
                return LAST_30_DAYS;
            } else if (days <= 365) {
                return LAST_YEAR;
            } else {
                return MORE_THAN_YEAR;
            }
        });
    }

    @Override
    public void deleteHistorySession(String sessionId) {
        // 历史会话的删除，需要删除数据库和ChatMemory中的数据
        var userId = UserContext.getUser();

        // 清除不受开关影响，避免关闭后删除会话却残留摘要
        if (this.enhancedMemory != null) {
            // 旧后台任务不能重新保存已清除摘要
            this.enhancedMemory.clearConversation(userId, sessionId);
        }

        // 构造删除条件
        var queryWrapper = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId);

        // 删除数据库数据
        super.remove(queryWrapper);

        // 获取对话id
        var conversationId = ChatService.getConversationId(sessionId);
        this.chatMemory.clear(conversationId);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        //更新数据
        super.lambdaUpdate()
                // 设置更新条件, 更新字段为title(最多设置前100个字符)，更新条件为sessionId和userId
                .set(ChatSession::getTitle, StrUtil.sub(title, 0, 100))
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, UserContext.getUser())
                .update();
    }
}
