package com.ragent.web.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.web.dto.LoginDTO;
import com.ragent.web.dto.LoginResult;
import com.ragent.web.dto.RegisterDTO;
import com.ragent.web.dto.UserVO;
import com.ragent.web.entity.User;
import com.ragent.web.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务：注册、登录、当前用户
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserVO register(RegisterDTO dto) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.username()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.username());
        user.setPassword(passwordEncoder.encode(dto.password()));
        user.setNickname(dto.nickname());
        user.setRole(dto.role() == null || dto.role().isBlank() ? "STUDENT" : dto.role());
        userMapper.insert(user);
        return toVO(user);
    }

    public LoginResult login(LoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.username()));
        if (user == null || !passwordEncoder.matches(dto.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return new LoginResult(StpUtil.getTokenValue(), toVO(user));
    }

    /** 当前登录用户（需 @SaCheckLogin） */
    public UserVO me() {
        return toVO(getRequired(StpUtil.getLoginIdAsLong()));
    }

    public User getRequired(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public UserVO toVO(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole(),
                user.getAvatar(), user.getBio(), user.getCreatedAt());
    }
}
