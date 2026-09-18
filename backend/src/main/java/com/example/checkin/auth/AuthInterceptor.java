package com.example.checkin.auth;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ID =
            "currentUserId";

    private static final String BEARER_PREFIX =
            "Bearer ";

    private final SessionService sessionService;

    public AuthInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        String authorization =
                request.getHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        String token = authorization
                .substring(BEARER_PREFIX.length())
                .trim();

        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Long userId = sessionService.getUserId(token);

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        request.setAttribute(CURRENT_USER_ID, userId);

        return true;
    }
}