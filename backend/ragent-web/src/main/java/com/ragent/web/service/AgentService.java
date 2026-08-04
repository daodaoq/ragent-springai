package com.ragent.web.service;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 智能体服务（P5 核心）：手动有界工具循环。
 */
public interface AgentService {

    Flux<ServerSentEvent<String>> agentStream(String question, String conversationId);

    record ToolCallEvent(String name, String arguments, String result) {
    }
}
