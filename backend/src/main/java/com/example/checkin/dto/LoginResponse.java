package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 登录成功的数据部分，包含令牌、有效期及不含密码哈希的用户资料。 */
@Getter
@AllArgsConstructor
public class LoginResponse {

    /** 原始不透明令牌，客户端后续通过 Authorization: Bearer 请求头提交。 */
    private final String token;
    /** Authorization 请求头使用的认证方案。 */
    private final String tokenType;
    /** 创建会话时使用的有效期，单位秒；不是每次访问后的剩余时间。 */
    private final long expiresIn;
    /** 已完成密码验证的用户资料。 */
    private final CurrentUserResponse user;
}
