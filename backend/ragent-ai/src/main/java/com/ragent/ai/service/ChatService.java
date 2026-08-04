package com.ragent.ai.service;

import reactor.core.publisher.Flux;

/**
 * AI 对话服务（P2 单轮流式；P5 接入 Redis 多轮会话记忆）。
 * 消息顺序：System + 历史 + 本次用户问题。
 */
public interface ChatService {

    Flux<String> stream(String message, String conversationId);
}
