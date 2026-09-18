package com.example.checkin.controller;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.dto.CurrentUserResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.example.checkin.common.ApiResponse;
import com.example.checkin.dto.LoginRequest;
import com.example.checkin.dto.LoginResponse;
import com.example.checkin.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        return ApiResponse.ok(authService.login(request));
    }

    /**
     * 获取当前登录用户信息。
     *
     * <p>用户 ID 由认证拦截器根据 Token 解析后写入当前请求，
     * 客户端不能自行指定 userId。
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        return ApiResponse.ok(
                authService.getCurrentUser(userId)
        );
    }

    /**
     * 退出当前登录状态。
     *
     * <p>该接口已经经过 AuthInterceptor 认证，
     * 因此 Authorization 中的 Bearer Token 一定存在且有效。
     * 退出时删除该 Token 对应的 Redis Session。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {

        String authorization =
                request.getHeader("Authorization");

        String token = authorization
                .substring("Bearer ".length())
                .trim();

        authService.logout(token);

        return ApiResponse.ok(null);
    }
}