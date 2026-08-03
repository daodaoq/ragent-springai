package com.ragent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求
 */
public record RegisterDTO(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 20, message = "用户名长度 3-20")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度 6-32")
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(max = 20, message = "昵称最多 20 个字符")
        String nickname,

        @Pattern(regexp = "STUDENT|TEACHER", message = "角色只能是 STUDENT 或 TEACHER")
        String role
) {
}
