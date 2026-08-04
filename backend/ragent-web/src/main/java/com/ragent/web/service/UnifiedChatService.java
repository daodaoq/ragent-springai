package com.ragent.web.service;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 统一对话服务：一次提问自动路由到 RAG / Agent / 普通对话。
 * 前端不再需要显式切换模式，路由依据查询处理管线的意图分类（RAG / AGENT / CHAT / OTHER）。
 */
public interface UnifiedChatService {

    /**
     * 统一流式接口：先发 mode 事件（rag/chat/agent），再委托对应引擎的 SSE 事件流。
     *
     * @param userId 登录用户 ID（未登录为 null），透传给 RAG 用于查询日志归属
     */
    Flux<ServerSentEvent<String>> stream(String question, String conversationId, Long userId);
}
