package com.ragent.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.service.ChatMemoryService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 多轮会话记忆实现（Redis）。
 * 按 conversationId 存最近 N 条 {role, content} 消息，TTL 7 天，每次写入刷新过期。
 * 仅用于 普通对话 / Agent 模式（RAG 保持无状态）。
 */
@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final String DEFAULT_CONVERSATION = "default";
    private static final int MAX_MESSAGES = 12;
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatMemoryServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatMessage> load(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void append(String conversationId, String userText, String assistantText) {
        List<ChatMessage> history = new ArrayList<>(load(conversationId));
        history.add(new ChatMessage("user", userText));
        history.add(new ChatMessage("assistant", assistantText));
        int size = history.size();
        if (size > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(size - MAX_MESSAGES, size));
        }
        try {
            redisTemplate.opsForValue().set(key(conversationId), objectMapper.writeValueAsString(history), TTL);
        } catch (Exception e) {
            // 记忆写入失败不影响对话本身
        }
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        String id = conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION : conversationId;
        return KEY_PREFIX + id;
    }
}
