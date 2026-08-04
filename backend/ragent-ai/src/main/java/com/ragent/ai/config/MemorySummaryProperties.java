package com.ragent.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话记忆配置（application.yml 中 ragent.memory.*）。
 * 滑动窗口 + 摘要压缩：窗口溢出（超过 {@code windowSize} 条）时把最旧消息交给 LLM 压缩为摘要，
 * 摘要同时持久化到 Redis 与 MySQL（ai_conversation_summary），在控制 Token 成本的同时保留长对话关键上下文。
 */
@Data
@ConfigurationProperties(prefix = "ragent.memory")
public class MemorySummaryProperties {

    /** 滑动窗口条数（保留的最近消息数，超过即压缩最旧部分） */
    private int windowSize = 12;

    /** 摘要压缩开关 */
    private boolean summaryEnabled = true;

    /** 摘要文本长度上限（字符），控制注入 prompt 的 Token 成本 */
    private int summaryMaxChars = 1500;
}
