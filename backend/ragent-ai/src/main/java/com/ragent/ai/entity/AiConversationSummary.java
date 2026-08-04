package com.ragent.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话记忆摘要（MySQL 持久化）。
 * 滑动窗口溢出时由 LLM 压缩最旧消息生成，Redis TTL 过期后仍可从本表恢复长对话上下文。
 */
@Data
@TableName("ai_conversation_summary")
public class AiConversationSummary {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话 ID（前端 convId） */
    private String conversationId;

    /** LLM 压缩后的对话摘要 */
    private String summary;

    /** 已压缩的原始消息条数 */
    private Integer messageCount;

    /** 最近一次摘要生成时间 */
    private LocalDateTime lastSummaryAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
