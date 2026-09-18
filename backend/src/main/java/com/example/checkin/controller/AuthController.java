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

/** 登录、当前用户查询及登出的 HTTP 入口；业务校验与存储访问委托给认证服务。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    /** 构造器注入认证业务服务。 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 匿名登录；请求体先进行非空校验，成功后以统一响应返回令牌。
     * @param request 用户名和原始密码，不接受客户端指定用户 ID
     * @return 令牌、认证方案、会话有效期和安全用户资料
     */
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
     * @param request 已由拦截器写入用户身份的当前请求
     * @return 数据库中的当前用户 ID 和用户名，不含密码哈希
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
     * 因此请求头格式已验证，会话在拦截器查询时存在；执行删除前仍可能过期。
     * 退出时仅删除该 Token 对应的 Redis Session，不影响其他设备的会话。
     * 同一令牌删除后再次请求，会在拦截器处被拒绝并返回未登录。
     * @param request 携带当前令牌的请求，不需要请求体
     * @return 删除操作完成后返回 code=0、data=null
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
