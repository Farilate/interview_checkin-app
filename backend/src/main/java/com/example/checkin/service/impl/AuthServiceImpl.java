package com.example.checkin.service.impl;

import com.example.checkin.auth.AuthCredentials;
import com.example.checkin.dto.CurrentUserResponse;
import com.example.checkin.common.ErrorCode;
import com.example.checkin.dto.LoginRequest;
import com.example.checkin.dto.LoginResponse;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.User;
import com.example.checkin.service.AuthService;
import com.example.checkin.service.SessionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证业务实现：MySQL 提供用户及密码哈希，Redis 保存登录会话。
 * <p>密码验证完成且会话写入成功后才返回登录结果；不向客户端暴露持久化用户对象。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;

    /** 注入用户查询、密码校验和会话访问组件，保持各自职责独立。 */
    public AuthServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            SessionService sessionService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    /**
     * 将用户名去除首尾空白并统一为小写后查询，再验证未经 trim 的原始密码。
     * <p>用户不存在与密码错误均返回相同业务错误码，避免通过错误提示区分账户是否存在。
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        String username = AuthCredentials.normalize(request.getUsername(), request.getPassword());

        User user = userMapper.findByUsername(username);

        if (user == null ||
                !passwordEncoder.matches(
                        request.getPassword(),
                        user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 此处失败会直接向上传递异常，不能返回一个没有服务端会话的令牌。
        String token = sessionService.createSession(user.getId());

        return new LoginResponse(token, "Bearer", sessionService.getTtlSeconds(),
                new CurrentUserResponse(String.valueOf(user.getId()), user.getUsername()));
    }

    /**
     * 按拦截器提供的身份重新查询用户，仅返回 ID 和用户名。
     * <p>用户已被删除时返回未登录；认证拦截器负责撤销本次请求对应的遗留会话。
     */
    @Override
    public CurrentUserResponse getCurrentUser(long userId) {
        User user = userMapper.findById(userId);

        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return new CurrentUserResponse(
                String.valueOf(user.getId()),
                user.getUsername()
        );
    }

    /** 撤销当前令牌；不删除用户数据，也不使该用户的其他登录会话失效。 */
    @Override
    public void logout(String token) {
        sessionService.deleteSession(token);
    }
}
