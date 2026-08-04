package com.ragent.web.config;

import cn.dev33.satoken.stp.StpInterface;
import com.ragent.web.entity.User;
import com.ragent.web.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 角色/权限提供者：从 sys_user.role 解析角色。
 * 必须实现，否则 @SaCheckRole 拿不到角色、对所有用户一律返回 403「角色权限不足」。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    /** 本项目未用细粒度权限码，统一放行（配合 @SaCheckRole 使用） */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userMapper.selectById(Long.valueOf(loginId.toString()));
        return user == null ? List.of() : List.of(user.getRole());
    }
}
