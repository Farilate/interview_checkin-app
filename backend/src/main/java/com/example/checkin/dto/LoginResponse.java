package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 登录成功的数据部分；当前仅返回令牌，用户资料需另行请求当前用户接口。 */
@Getter
@AllArgsConstructor
public class LoginResponse {

    /** 原始不透明令牌，客户端后续通过 Authorization: Bearer 请求头提交。 */
    private final String token;
}
