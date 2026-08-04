package com.ragent.web.dto;

/**
 * AI 回答反馈提交
 */
public record FeedbackDTO(String conversationId, String question, String answer, Integer rating) {
}
