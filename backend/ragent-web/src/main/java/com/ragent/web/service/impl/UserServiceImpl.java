package com.ragent.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import com.ragent.common.result.PageResult;
import com.ragent.web.dto.ChangePasswordDTO;
import com.ragent.web.dto.LoginDTO;
import com.ragent.web.dto.LoginResult;
import com.ragent.web.dto.RegisterDTO;
import com.ragent.web.dto.UpdateProfileDTO;
import com.ragent.web.dto.UserVO;
import com.ragent.web.entity.User;
import com.ragent.web.mapper.UserMapper;
import com.ragent.web.service.FileService;
import com.ragent.web.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 用户服务实现：注册、登录、当前用户、个人资料、密码、管理员用户管理
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final FileService fileService;

    /** 允许的角色枚举值 */
    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    @Override
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

    @Override
    public LoginResult login(LoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.username()));
        if (user == null || !passwordEncoder.matches(dto.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return new LoginResult(StpUtil.getTokenValue(), toVO(user));
    }

    @Override
    public UserVO me() {
        return me(StpUtil.getLoginIdAsLong());
    }

    @Override
    public UserVO me(Long userId) {
        return toVO(getRequired(userId));
    }

    @Override
    public User getRequired(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    @Override
    public UserVO toVO(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole(),
                fileService.getUrl(user.getAvatar()), user.getBio(), user.getCreatedAt());
    }

    @Override
    public UserVO updateProfile(UpdateProfileDTO dto) {
        User user = getRequired(StpUtil.getLoginIdAsLong());
        if (dto.nickname() != null && !dto.nickname().isBlank()) {
            user.setNickname(dto.nickname().trim());
        }
        if (dto.avatar() != null) {
            // 允许传空串清空头像
            String avatar = dto.avatar().isBlank() ? null : dto.avatar().trim();
            if (avatar != null && !fileService.exists(avatar)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件不存在，请先上传");
            }
            user.setAvatar(avatar);
        }
        if (dto.bio() != null) {
            user.setBio(dto.bio().isBlank() ? null : dto.bio().trim());
        }
        userMapper.updateById(user);
        return toVO(user);
    }

    @Override
    public void changePassword(ChangePasswordDTO dto) {
        User user = getRequired(StpUtil.getLoginIdAsLong());
        if (!passwordEncoder.matches(dto.oldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原密码错误");
        }
        if (dto.oldPassword().equals(dto.newPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(dto.newPassword()));
        userMapper.updateById(user);
        // 改密后踢掉该用户所有登录会话（含其他端），需重新登录
        StpUtil.logout(user.getId());
    }

    // ===== 管理员用户管理 =====

    @Override
    public PageResult<UserVO> adminPage(long pageNum, long pageSize, String keyword, String role) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword));
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(User::getRole, role);
        }
        Page<User> page = userMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<UserVO> records = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public UserVO adminUpdateRole(Long userId, String role) {
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色只能是 STUDENT / TEACHER / ADMIN");
        }
        Long currentId = StpUtil.getLoginIdAsLong();
        if (currentId.equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能修改自己的角色");
        }
        User user = getRequired(userId);
        if ("ADMIN".equals(user.getRole()) && !"ADMIN".equals(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能降级其他管理员角色");
        }
        user.setRole(role);
        userMapper.updateById(user);
        return toVO(user);
    }

    @Override
    public void adminDelete(Long userId) {
        Long currentId = StpUtil.getLoginIdAsLong();
        if (currentId.equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除自己");
        }
        User user = getRequired(userId);
        if ("ADMIN".equals(user.getRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除管理员账号");
        }
        // 逻辑删除（@TableLogic 已配置，deleteById 会将 deleted 置 1）
        userMapper.deleteById(user.getId());
    }
}
