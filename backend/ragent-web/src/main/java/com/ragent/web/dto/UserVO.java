package com.ragent.web.dto;

import java.time.LocalDateTime;

/**
 * 用户视图（不含密码）
 */
public record UserVO(
        Long id,
        String username,
        String nickname,
        String role,
        String avatar,
        String bio,
        LocalDateTime createdAt
) {
}
