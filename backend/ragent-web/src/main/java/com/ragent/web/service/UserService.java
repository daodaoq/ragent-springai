package com.ragent.web.service;

import com.ragent.common.result.PageResult;
import com.ragent.web.dto.ChangePasswordDTO;
import com.ragent.web.dto.LoginDTO;
import com.ragent.web.dto.LoginResult;
import com.ragent.web.dto.RegisterDTO;
import com.ragent.web.dto.UpdateProfileDTO;
import com.ragent.web.dto.UserVO;
import com.ragent.web.entity.User;

/**
 * 用户服务：注册、登录、当前用户、个人资料、密码、管理员用户管理
 */
public interface UserService {

    UserVO register(RegisterDTO dto);

    LoginResult login(LoginDTO dto);

    /** 当前登录用户（需 @SaCheckLogin） */
    UserVO me();

    User getRequired(Long userId);

    UserVO toVO(User user);

    /** 修改当前登录用户资料（昵称/头像/简介） */
    UserVO updateProfile(UpdateProfileDTO dto);

    /** 修改当前登录用户密码 */
    void changePassword(ChangePasswordDTO dto);

    // ===== 管理员用户管理 =====

    /** 用户分页列表（管理员） */
    PageResult<UserVO> adminPage(long pageNum, long pageSize, String keyword, String role);

    /** 修改用户角色（管理员） */
    UserVO adminUpdateRole(Long userId, String role);

    /** 删除用户（逻辑删除，管理员；不可删除自己） */
    void adminDelete(Long userId);
}
