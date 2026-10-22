package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.vo.ChatEventVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把内部 PLAN 状态映射到现有聊天事件协议。 */
@Component
public class PlanEventMapper {

    public ChatEventVO progress(AgentProgress progress) {
        // 只创建包含安全字段的进度载荷
        var payload = new LinkedHashMap<String, Object>();
        // 发送约定的进度类型
        payload.put("type", progress.type().name());
        // 有步骤时附上稳定步骤 ID
        if (progress.stepId() != null) {
            // 避免发送能力输入或模型原文
            payload.put("stepId", progress.stepId());
        }
        // 步骤结束时附上状态
        if (progress.status() != null) {
            // 不暴露失败堆栈和内部结果
            payload.put("status", progress.status().name());
        }
        // 使用新增的 PROGRESS 事件类型
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.PROGRESS.getValue()).eventData(Map.copyOf(payload)).build();
    }

    public List<ChatEventVO> finalEvents(PlanFinalAnswer answer) {
        // 保存现有 DATA、PARAM、STOP 事件序列
        var events = new ArrayList<ChatEventVO>();
        // 最终文本仍使用 DATA
        events.add(ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData(answer.text()).build());
        // 有实际引用时才发送来源参数
        if (!answer.sources().isEmpty()) {
            // 来源仅包含 EvidenceSnapshot 中被使用的条目
            events.add(ChatEventVO.builder().eventType(ChatEventTypeEnum.PARAM.getValue()).eventData(Map.of("sources", answer.sources())).build());
        }
        // 无论有无引用都发送正常结束标记
        events.add(ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build());
        // 固定对外事件列表
        return List.copyOf(events);
    }
}
