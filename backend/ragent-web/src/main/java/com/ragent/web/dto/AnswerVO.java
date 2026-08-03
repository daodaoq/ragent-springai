package com.ragent.web.dto;

import java.time.LocalDateTime;

/**
 * 回答视图
 */
public record AnswerVO(
        Long id,
        Long questionId,
        Long userId,
        String content,
        boolean accepted,
        LocalDateTime createdAt,
        UserVO author
) {
}
