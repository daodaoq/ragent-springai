package com.ragent.web.controller;

import com.ragent.ai.service.ChatService;
import com.ragent.web.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 对话接口（SSE 流式）
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatService chatService;
    private final RagService ragService;

    /** 普通对话流式 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        return chatService.stream(request.message() == null ? "" : request.message());
    }

    /** RAG 知识库问答流式（先 sources 事件，再 content 事件） */
    @PostMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ragStream(@RequestBody ChatRequest request) {
        return ragService.ragStream(request.message() == null ? "" : request.message());
    }

    public record ChatRequest(String message) {
    }
}
