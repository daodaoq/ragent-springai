package com.ragent.web.controller;

import com.ragent.common.result.Result;
import com.ragent.web.service.AgentService;
import com.ragent.web.service.ChatMemoryService;
import com.ragent.web.service.ChatService;
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
 * AI 对话接口（SSE 流式）。
 * 普通对话 / RAG / Agent 三种模式；conversationId 缺省时向后兼容（多轮记忆退化为 default）。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatService chatService;
    private final RagService ragService;
    private final AgentService agentService;
    private final ChatMemoryService chatMemoryService;

    /** 普通对话流式（P5 起带多轮记忆） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        return chatService.stream(request.message() == null ? "" : request.message(), request.conversationId());
    }

    /** RAG 知识库问答流式（先 sources 事件，再 content 事件；无状态） */
    @PostMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ragStream(@RequestBody ChatRequest request) {
        return ragService.ragStream(request.message() == null ? "" : request.message());
    }

    /** Agent 智能体流式（工具调用事件 tool-call + 最终答案 content） */
    @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentStream(@RequestBody ChatRequest request) {
        return agentService.agentStream(request.message() == null ? "" : request.message(), request.conversationId());
    }

    /** 清空某会话的多轮记忆 */
    @PostMapping("/memory/clear")
    public Result<Void> clearMemory(@RequestBody ChatRequest request) {
        chatMemoryService.clear(request.conversationId());
        return Result.success();
    }

    public record ChatRequest(String message, String conversationId) {
    }
}
