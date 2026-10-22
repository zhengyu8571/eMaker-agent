package com.tianji.aigc.service.impl;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.tianji.aigc.service.AudioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.audio.speech.SpeechPrompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai", name = "audio-type", havingValue = "OPENAI")
public class AudioServiceImpl implements AudioService {

    private final OpenAiAudioSpeechModel openAiAudioSpeechModel;
    private final OpenAiAudioTranscriptionModel openAiAudioTranscriptionModel;

    @Override
    public ResponseBodyEmitter ttsStream(String text) {
        log.info("开始进行文字转语音：{}", text);
        // 定义响应对象
        var emitter = new ResponseBodyEmitter();

        // 调用OpenAI进行文字转语音
        var speechPrompt = new SpeechPrompt(text);
        var streamResponse = this.openAiAudioSpeechModel.stream(speechPrompt);
        // 订阅流式输出，将大模型响应的内容，以流式的方式输出给前端
        streamResponse.subscribe(speechResponse -> {
                    try {
                        // 响应的语音内容
                        byte[] bytes = speechResponse.getResult().getOutput();
                        // 发送给前端
                        emitter.send(bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }

    @Override
    public String stt(MultipartFile multipartFile) {
        // 根据上传的音频文件获取文件资源
        var resource = multipartFile.getResource();
        // 基于文件资源，创建一个音频转文字的提示
        var prompt = new AudioTranscriptionPrompt(resource);
        // 调用OpenAI进行音频转文字
        var response = this.openAiAudioTranscriptionModel.call(prompt);
        // 获取文本内容
        var text = response.getResult().getOutput();
        log.info("音频转文字结果：{}", text);

        // 获取到文本内容有可能会有繁体中文，需要转化为简体中文
        return ZhConverterUtil.toSimple(text);
    }
}
