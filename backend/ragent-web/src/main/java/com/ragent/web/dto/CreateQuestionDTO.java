package com.ragent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建问题请求
 */
public record CreateQuestionDTO(

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最多 200 字")
        String title,

        @NotBlank(message = "内容不能为空")
        String content,

        List<String> tags
) {
}
