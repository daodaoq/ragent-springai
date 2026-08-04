package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.ai.config.MemorySummaryProperties;
import com.ragent.ai.entity.AiConversationSummary;
import com.ragent.ai.mapper.AiConversationSummaryMapper;
import com.ragent.ai.service.AiRetry;
import com.ragent.ai.service.ChatMemoryService;
import com.ragent.ai.service.MemorySummaryService;
import com.ragent.common.context.RagentThreadPools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 会话记忆摘要实现：滑动窗口溢出时异步 LLM 压缩，Redis（chat:summary:{convId}）+ MySQL 双持久化。
 * 摘要线程池独立命名（memory-summary），失败仅告警，绝不阻塞对话主链路。
 */
@Slf4j
@Service
public class MemorySummaryServiceImpl implements MemorySummaryService {

    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String DEFAULT_CONVERSATION = "default";
    private static final Duration TTL = Duration.ofDays(7);

    private static final String SYSTEM_PROMPT = """
            你是对话摘要助手。把一段多轮对话压缩成简洁的中文摘要，保留：关键事实、用户偏好、
            未解决的问题、已给出的重要结论。按时间顺序组织，不要复述逐字内容，不要编造。""";

    private final StringRedisTemplate redis;
    private final AiConversationSummaryMapper summaryMapper;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final MemorySummaryProperties props;
    private final ExecutorService summaryExecutor;

    public MemorySummaryServiceImpl(StringRedisTemplate redis, AiConversationSummaryMapper summaryMapper,
                                    ObjectProvider<ChatClient> chatClientProvider, MemorySummaryProperties props) {
        this.redis = redis;
        this.summaryMapper = summaryMapper;
        this.chatClientProvider = chatClientProvider;
        this.props = props;
        // TTL + MDC 透传：摘要生成的 LLM 调用与日志带 traceId/userId
        this.summaryExecutor = RagentThreadPools.newExecutor("memory-summary", 1, 2, 100,
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Override
    public void summarizeAsync(String conversationId, List<ChatMemoryService.ChatMessage> evicted) {
        if (!props.isSummaryEnabled() || evicted == null || evicted.isEmpty()) {
            return;
        }
        String convId = conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION : conversationId;
        try {
            summaryExecutor.submit(() -> {
                try {
                    doSummarize(convId, evicted);
                } catch (Exception e) {
                    log.warn("生成会话摘要失败 convId={}: {}", convId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("摘要队列已满，丢弃本次压缩 convId={}", convId);
        }
    }

    private void doSummarize(String convId, List<ChatMemoryService.ChatMessage> evicted) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return;
        }
        String existing = null;
        try {
            existing = redis.opsForValue().get(summaryKey(convId));
        } catch (Exception ignore) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已有摘要：\n").append(existing == null || existing.isBlank() ? "（无）" : existing).append("\n\n");
        sb.append("新增对话（最近被滑出窗口的消息）：\n");
        for (ChatMemoryService.ChatMessage m : evicted) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        sb.append("\n请综合已有摘要与新增对话，输出一份更新后的摘要，不超过 ")
                .append(props.getSummaryMaxChars()).append(" 字。");
        String result = AiRetry.callWithRetry(() -> chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(sb.toString())
                .call()
                .content());
        if (result == null || result.isBlank()) {
            return;
        }
        if (result.length() > props.getSummaryMaxChars()) {
            result = result.substring(0, props.getSummaryMaxChars());
        }
        try {
            redis.opsForValue().set(summaryKey(convId), result, TTL);
        } catch (Exception e) {
            log.warn("摘要写 Redis 失败 convId={}: {}", convId, e.getMessage());
        }
        upsertMySql(convId, result, evicted.size());
        // 该日志在 memory-summary 异步线程输出；若 TTL 透传生效，MDC 会带 traceId/userId（logback %mdc）
        log.debug("会话摘要已生成 convId={}, 长度={}, 压缩消息数={}", convId, result.length(), evicted.size());
    }

    private void upsertMySql(String convId, String summary, int count) {
        try {
            AiConversationSummary row = summaryMapper.selectOne(new LambdaQueryWrapper<AiConversationSummary>()
                    .eq(AiConversationSummary::getConversationId, convId));
            if (row == null) {
                row = new AiConversationSummary();
                row.setConversationId(convId);
                row.setSummary(summary);
                row.setMessageCount(count);
                row.setLastSummaryAt(LocalDateTime.now());
                summaryMapper.insert(row);
            } else {
                row.setSummary(summary);
                row.setMessageCount(row.getMessageCount() + count);
                row.setLastSummaryAt(LocalDateTime.now());
                summaryMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("摘要写 MySQL 失败 convId={}: {}", convId, e.getMessage());
        }
    }

    @Override
    public String getSummary(String conversationId) {
        String convId = conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION : conversationId;
        try {
            String s = redis.opsForValue().get(summaryKey(convId));
            if (s != null && !s.isBlank()) {
                return s;
            }
        } catch (Exception ignore) {
        }
        try {
            AiConversationSummary row = summaryMapper.selectOne(new LambdaQueryWrapper<AiConversationSummary>()
                    .eq(AiConversationSummary::getConversationId, convId));
            return row == null ? null : row.getSummary();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void clear(String conversationId) {
        String convId = conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION : conversationId;
        try {
            redis.delete(summaryKey(convId));
        } catch (Exception ignore) {
        }
        try {
            summaryMapper.delete(new LambdaQueryWrapper<AiConversationSummary>()
                    .eq(AiConversationSummary::getConversationId, convId));
        } catch (Exception ignore) {
        }
    }

    private static String summaryKey(String convId) {
        return SUMMARY_KEY_PREFIX + convId;
    }
}
