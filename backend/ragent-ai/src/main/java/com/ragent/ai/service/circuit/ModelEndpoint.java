package com.ragent.ai.service.circuit;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 一个候选模型端点：模型实例 + 独立熔断器 + 名字。
 * 降级链按 List 顺序（优先高优先级的端点；熔断中则跳过尝试下一个）。
 */
public record ModelEndpoint(String name, ChatModel model, CircuitBreaker breaker) {
}
