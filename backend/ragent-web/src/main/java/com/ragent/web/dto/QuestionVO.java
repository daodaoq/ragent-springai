package com.ragent.web.dto;

import com.ragent.web.entity.Tag;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问题视图（含作者、标签、回答列表）
 */
public record QuestionVO(
        Long id,
        String title,
        String content,
        Long userId,
        String status,
        Long bestAnswerId,
        Integer viewCount,
        Integer answerCount,
        LocalDateTime createdAt,
        UserVO author,
        List<Tag> tags,
        List<AnswerVO> answers
) {
}
