package com.ragent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 * P8-8c：自注册一律 STUDENT，不再接收 role 字段——教师/管理员角色由管理员在用户管理页授予，
 * 避免"任何人自注册成 TEACHER 即可查看全量查询日志/反馈明文"的越权敞口。
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
        String nickname
) {
}
