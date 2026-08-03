package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.ragent.common.result.Result;
import com.ragent.web.dto.LoginDTO;
import com.ragent.web.dto.LoginResult;
import com.ragent.web.dto.RegisterDTO;
import com.ragent.web.dto.UserVO;
import com.ragent.web.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginResult> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    @GetMapping("/me")
    @SaCheckLogin
    public Result<UserVO> me() {
        return Result.success(userService.me());
    }

    @PostMapping("/logout")
    @SaCheckLogin
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.success();
    }
}
