package com.ragent.web.dto;

/**
 * 登录结果
 */
public record LoginResult(String token, UserVO user) {
}
