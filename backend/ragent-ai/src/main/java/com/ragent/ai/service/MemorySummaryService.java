package com.ragent.ai.service;

import java.util.List;

/**
 * 会话记忆摘要服务：滑动窗口溢出时把最旧消息交给 LLM 压缩为摘要，Redis + MySQL 双持久化。
 */
public interface MemorySummaryService {

    /** 异步压缩被滑出窗口的消息并持久化（Redis + MySQL）；不阻塞主链路。 */
    void summarizeAsync(String conversationId, List<ChatMemoryService.ChatMessage> evicted);

    /** 读取会话摘要（Redis 优先，MySQL 兜底）；无则返回 null。 */
    String getSummary(String conversationId);

    /** 清空会话摘要（随会话清空调用）。 */
    void clear(String conversationId);
}
