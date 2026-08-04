package com.ragent.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ragent.common.result.PageResult;
import com.ragent.common.result.Result;
import com.ragent.web.dto.UserVO;
import com.ragent.web.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（仅 ADMIN）
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
public class UserController {

    private final UserService userService;

    /** 用户分页列表 */
    @GetMapping("/list")
    public Result<PageResult<UserVO>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role) {
        return Result.success(userService.adminPage(pageNum, pageSize, keyword, role));
    }

    /** 修改用户角色 */
    @PutMapping("/{id}/role")
    public Result<UserVO> updateRole(@PathVariable Long id, @RequestParam String role) {
        return Result.success(userService.adminUpdateRole(id, role));
    }

    /** 删除用户（逻辑删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.adminDelete(id);
        return Result.success();
    }
}
