package com.tianji.learning.service.impl;

import cn.hutool.core.util.StrUtil;
import com.tianji.api.client.aigc.AigcClient;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.service.AIService;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIServiceImpl implements AIService {

    private final AigcClient aigcClient;
    private final IInteractionReplyService iInteractionReplyService;

    @Value("${tj.ai.user-id:9999}")
    private Long aiUserId;

    @Override
    public void autoReply(InteractionQuestion interactionQuestion) {
        // 构建包含完整问题信息的查询模板（标题+描述）
        var question = StrUtil.format("""
                这是一个学生提出的问题，请以专业的角度进行回答，不要随意编造。
                标题：{} 。
                描述：{} 。""", interactionQuestion.getTitle(), interactionQuestion.getDescription());

        // 调用AI大模型，获取回答
        // 设置当前用户id，否在会出现401错误
        UserContext.setUser(interactionQuestion.getUserId());
        var reply = this.aigcClient.chatText(question);

        // 封装回答数据
        var replyDTO = ReplyDTO.builder()
                .content(reply)
                .anonymity(false)       // 明确显示系统回复身份
                .questionId(interactionQuestion.getId())  // 关联原始问题ID
                .isStudent(false)       // 标记为非学生回复
                .userId(aiUserId)  // 当前回复这个问题的人的id
                .build();

        // 保存回答
        this.iInteractionReplyService.saveReply(replyDTO);
    }
}
