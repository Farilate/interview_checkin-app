package com.example.checkin.service.impl;

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

import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;

    public AuthServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            SessionService sessionService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername()
                .trim()
                .toLowerCase(Locale.ROOT);

        User user = userMapper.findByUsername(username);

        if (user == null ||
                !passwordEncoder.matches(
                        request.getPassword(),
                        user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        String token = sessionService.createSession(user.getId());

        return new LoginResponse(token);
    }
    @Override
    public CurrentUserResponse getCurrentUser(long userId) {
        User user = userMapper.findById(userId);

        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return new CurrentUserResponse(
                user.getId(),
                user.getUsername()
        );
    }

    @Override
    public void logout(String token) {
        sessionService.deleteSession(token);
    }
}