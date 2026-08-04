package com.ragent.web.service.impl;

import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.ChatService;
import com.ragent.ai.service.QueryPipeline;
import com.ragent.ai.service.RagService;
import com.ragent.web.service.AgentService;
import com.ragent.web.service.UnifiedChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 统一对话服务实现：意图自动路由（前端不再分三个对话框）。
 * 三种引擎共享同一 conversationId 的多轮记忆（各引擎自行读写 Redis）。
 * <p>
 * 先跑一次查询处理管线（gateByIntent=false，路由由本服务负责），按意图分发：
 * <ul>
 *   <li>AGENT → AgentService（工具循环，SSE: tool-call + content）</li>
 *   <li>RAG → RagService（检索，SSE: rewritten + sources + content，复用已算好的管线产物）</li>
 *   <li>CHAT/OTHER → ChatService（纯对话，SSE: content）</li>
 * </ul>
 * 每条回答前置一个 mode 事件（rag/chat/agent）供前端展示引擎徽标。
 */
@Service
@RequiredArgsConstructor
public class UnifiedChatServiceImpl implements UnifiedChatService {

    private final QueryPipeline queryPipeline;
    private final RagService ragService;
    private final ChatService chatService;
    private final AgentService agentService;
    private final ChatMemoryService chatMemoryService;

    @Override
    public Flux<ServerSentEvent<String>> stream(String question, String conversationId, Long userId) {
        String q = question == null ? "" : question;
        List<ChatMemoryService.ChatMessage> history = chatMemoryService.load(conversationId);
        QueryPipeline.ProcessedQuery pq = queryPipeline.run(q, history, false);
        String intent = pq.intent() == null ? "RAG" : pq.intent().toUpperCase();
        return switch (intent) {
            case "AGENT" -> Flux.concat(
                    Flux.just(mode("agent")),
                    agentService.agentStream(q, conversationId));
            case "CHAT", "OTHER" -> Flux.concat(
                    Flux.just(mode("chat")),
                    chatService.stream(q, conversationId).map(c -> sse("content", c)));
            default -> Flux.concat( // RAG 或未知意图：兜底走知识库检索
                    Flux.just(mode("rag")),
                    ragService.ragStream(q, conversationId, userId, pq));
        };
    }

    private static ServerSentEvent<String> mode(String m) {
        return ServerSentEvent.<String>builder().event("mode").data("\"" + m + "\"").build();
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
