package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 当前登录用户响应对象。
 *
 * <p>只返回允许暴露给客户端的用户信息，
 * 不直接返回 User 持久化对象，避免 passwordHash 等敏感字段泄露。
 */
@Getter
@AllArgsConstructor
public class CurrentUserResponse {

    /**
     * 当前登录用户的数据库主键。
     * <p>使用十进制字符串，避免 H5 JavaScript 对大整数发生精度丢失。
     */
    private final String id;

    /**
     * 当前登录用户名。
     */
    private final String username;
}
