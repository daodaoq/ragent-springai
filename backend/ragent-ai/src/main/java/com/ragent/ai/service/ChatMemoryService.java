package com.ragent.ai.service;

import java.util.List;

/**
 * 多轮会话记忆（Redis 滑动窗口 + LLM 摘要压缩）。
 * 按 conversationId 存最近 N 条 {role, content} 消息，TTL 7 天，每次写入刷新过期；
 * 窗口溢出时最旧消息由 MemorySummaryService 压缩为摘要并持久化（Redis + MySQL）。
 */
public interface ChatMemoryService {

    record ChatMessage(String role, String content) {
    }

    /** 读取原始窗口历史（不含摘要）；key 不存在或 JSON 损坏时返回空列表 */
    List<ChatMessage> load(String conversationId);

    /**
     * 读取带摘要的历史：有摘要时在最前注入一条 system 消息「【对话历史摘要】…」。
     * 供 普通对话 / Agent 直接映射为 Spring AI Message；RAG 改写只取最近几轮 user 消息，用 load() 即可。
     */
    List<ChatMessage> loadWithSummary(String conversationId);

    /** 追加一轮问答，裁剪到最近 windowSize 条并刷新 TTL；溢出部分异步压缩为摘要 */
    void append(String conversationId, String userText, String assistantText);

    void clear(String conversationId);
}
