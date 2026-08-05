package com.ragent.web.dto;

import java.time.LocalDateTime;

/**
 * AI 回答反馈明细（管理端查看用，带评价用户昵称）
 */
public record FeedbackVO(
        Long id,
        Long userId,
        String nickname,
        String conversationId,
        String question,
        String answer,
        /** 1 赞 / -1 踩 */
        Integer rating,
        /** 原回答 traceId（可关联查询日志/ELK） */
        String traceId,
        LocalDateTime createdAt) {
}
