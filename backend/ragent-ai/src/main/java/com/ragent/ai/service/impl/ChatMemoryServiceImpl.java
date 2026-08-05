package com.ragent.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.ai.config.MemorySummaryProperties;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.MemorySummaryService;
import com.ragent.common.context.RagentContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 多轮会话记忆实现（Redis 滑动窗口 + LLM 摘要压缩）。
 * 按 conversationId 存最近 {@link MemorySummaryProperties#getWindowSize()} 条 {role, content} 消息，
 * TTL 7 天，每次写入刷新过期；窗口溢出时把最旧消息交给 MemorySummaryService 异步压缩为摘要。
 * <p>
 * P8-3c：存储键按当前请求用户作用域隔离（登录 → {@code u{userId}}，匿名 → {@code anon}），
 * conversationId 为空回落 default 仍在本用户作用域内，杜绝跨用户串话。
 * 升级后旧的无作用域键自然过期，登录态变化会得到全新的记忆（会话不跨账号）。
 */
@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final String DEFAULT_CONVERSATION = "default";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemorySummaryService memorySummaryService;
    private final MemorySummaryProperties props;

    public ChatMemoryServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                 MemorySummaryService memorySummaryService, MemorySummaryProperties props) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.memorySummaryService = memorySummaryService;
        this.props = props;
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
    public List<ChatMessage> loadWithSummary(String conversationId) {
        List<ChatMessage> messages = load(conversationId);
        // 窗口本身已过期时，仍可从摘要恢复长对话上下文（不丢）
        if (messages.isEmpty()) {
            String summary = memorySummaryService.getSummary(conversationId);
            return (summary == null || summary.isBlank()) ? List.of() : List.of(new ChatMessage("system", summary));
        }
        String summary = memorySummaryService.getSummary(conversationId);
        if (summary == null || summary.isBlank()) {
            return messages;
        }
        List<ChatMessage> out = new ArrayList<>(messages.size() + 1);
        out.add(new ChatMessage("system", summary));
        out.addAll(messages);
        return out;
    }

    @Override
    public void append(String conversationId, String userText, String assistantText) {
        List<ChatMessage> history = new ArrayList<>(load(conversationId));
        history.add(new ChatMessage("user", userText));
        history.add(new ChatMessage("assistant", assistantText));
        int size = history.size();
        int window = Math.max(4, props.getWindowSize());
        if (size > window) {
            List<ChatMessage> overflow = new ArrayList<>(history.subList(0, size - window));
            history = new ArrayList<>(history.subList(size - window, size));
            // 异步把被裁掉的最旧消息压缩进摘要（不阻塞对话主链路）
            memorySummaryService.summarizeAsync(conversationId, overflow);
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
        memorySummaryService.clear(conversationId);
    }

    private static String key(String conversationId) {
        String id = conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION : conversationId;
        return KEY_PREFIX + RagentContext.userScope() + ":" + id;
    }
}
