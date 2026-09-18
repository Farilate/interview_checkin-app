package com.example.checkin.auth;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 登录和演示账户共用的凭证边界，确保初始化的账户也符合登录规则。 */
public final class AuthCredentials {
    private AuthCredentials() { }

    /**
     * 校验后返回规范化用户名；密码原样保留，按 UTF-8 字节数校验 BCrypt 输入边界。
     * 不在异常中携带用户名或密码，避免敏感输入出现在响应和日志中。
     * @param username 原始用户名，允许首尾空白，但规范化后必须满足 ASCII 范围及长度
     * @param password 原始密码，不执行 trim
     * @return 去除首尾空白并统一为小写的用户名
     * @throws BusinessException 输入不满足规则时使用统一参数错误码
     */
    public static String normalize(String username, String password) {
        if (username == null || password == null || password.isBlank() || password.length() > 72) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String normalized = username.trim();
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (!normalized.matches("[A-Za-z0-9_]{3,32}") || passwordBytes < 8 || passwordBytes > 72) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
