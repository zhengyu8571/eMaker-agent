package com.tianji.aigc.controller;

import com.tianji.aigc.service.AudioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
@RequestMapping("/audio")
@RequiredArgsConstructor
public class AudioController {

    private final AudioService audioService;

    /**
     * 文本转语音
     */
    @PostMapping(value = "tts-stream", produces = "audio/mp3")
    public ResponseBodyEmitter ttsStream(@RequestBody String text) {
        return this.audioService.ttsStream(text);
    }

    /**
     * 语音转文本
     */
    @PostMapping("stt")
    public String stt(@RequestParam("audioFile") MultipartFile multipartFile) {
        return this.audioService.stt(multipartFile);
    }
}
