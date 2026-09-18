package com.example.checkin.service;

import com.example.checkin.dto.CurrentUserResponse;
import com.example.checkin.dto.LoginRequest;
import com.example.checkin.dto.LoginResponse;

/**
 * 认证相关业务接口。
 *
 * <p>负责登录、登出以及当前登录用户信息查询，
 * Controller 只处理 HTTP 请求，不直接访问 Mapper。
 */
public interface AuthService {

    /**
     * 校验用户名和密码，登录成功后创建 Session 并返回 Token。
     * @param request 已完成请求体非空校验的登录参数
     * @return 包含原始令牌的登录结果
     */
    LoginResponse login(LoginRequest request);

    /**
     * 根据已经通过认证的用户 ID 查询当前用户信息。
     *
     * @param userId 当前登录用户 ID
     * @return 可安全返回给客户端的用户信息
     */
    CurrentUserResponse getCurrentUser(long userId);

    /**
     * 撤销当前登录会话。
     * @param token 当前请求携带的原始令牌，而非 Redis 键中的摘要
     */
    void logout(String token);
}
