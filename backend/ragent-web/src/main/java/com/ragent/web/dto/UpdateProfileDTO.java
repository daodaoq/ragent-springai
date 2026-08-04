package com.ragent.web.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改个人资料请求（昵称/头像/简介，均可选，仅传需要改的字段）
 */
public record UpdateProfileDTO(

        @Size(min = 1, max = 20, message = "昵称长度 1-20")
        String nickname,

        @Size(max = 255, message = "头像URL最多 255 个字符")
        String avatar,

        @Size(max = 255, message = "个人简介最多 255 个字符")
        String bio
) {
}
