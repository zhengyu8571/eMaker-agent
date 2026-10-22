package com.tianji.aigc.controller;

import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.debug.AiDebugTrace;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.aigc.vo.TemplateVO;
import com.tianji.common.annotations.NoWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    // 仅在开启调试时保存本次请求的调用流水
    private final AiDebugTrace debugTrace;

    // 优化：将文本的模版写入到nacos中
    private static final TemplateVO TEMPLATE_VO = new TemplateVO();

    @NoWrapper // 响应的结果不需要包装
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chatDTO) {
        // 路由、异步执行和流式结果共用同一个文件
        return this.debugTrace.stream(chatDTO.getQuestion(), chatDTO.getSessionId(), () -> this.chatService.chat(chatDTO.getQuestion(), chatDTO.getSessionId()));
    }

    @PostMapping("/stop")
    public void stop(@RequestParam("sessionId") String sessionId) {
        this.chatService.stop(sessionId);
    }

    @PostMapping("/text")
    public String chatText(@RequestBody String question) {
        // 普通文本问答同样记录实际模型输入输出
        return this.debugTrace.callRequest(question, () -> this.chatService.chatText(question));
    }

    @GetMapping("/templates")
    public TemplateVO getTemplates() {
        return TEMPLATE_VO;
    }

}
