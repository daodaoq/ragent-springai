package com.ragent.ai.service;

import java.util.List;

/**
 * 多轮会话记忆（Redis）。
 * 按 conversationId 存最近 N 条 {role, content} 消息，TTL 7 天，每次写入刷新过期。
 * 仅用于 普通对话 / Agent 模式（RAG 保持无状态）。
 */
public interface ChatMemoryService {

    record ChatMessage(String role, String content) {
    }

    /** 读取历史；key 不存在或 JSON 损坏时返回空列表 */
    List<ChatMessage> load(String conversationId);

    /** 追加一轮问答，裁剪到最近 MAX_MESSAGES 条并刷新 TTL */
    void append(String conversationId, String userText, String assistantText);

    void clear(String conversationId);
}
