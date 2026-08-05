package com.ragent.web.dto;

/**
 * AI 回答反馈提交
 *
 * @param traceId 原回答的 traceId（可选；前端拿不到时服务端按会话+问题从查询日志回填）
 */
public record FeedbackDTO(String conversationId, String question, String answer, Integer rating, String traceId) {
}
