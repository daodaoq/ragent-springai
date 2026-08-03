package com.ragent.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求
 */
public record LoginDTO(

        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
