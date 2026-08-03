package com.ragent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建回答请求
 */
public record CreateAnswerDTO(

        @NotNull(message = "问题ID不能为空")
        Long questionId,

        @NotBlank(message = "回答内容不能为空")
        String content
) {
}
