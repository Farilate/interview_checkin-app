package com.example.checkin.dto;


import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录请求体；Lombok 生成访问方法，Bean Validation 负责非空白校验。
 * <p>服务层通过 AuthCredentials 校验规范化后的用户名范围和密码 UTF-8 字节数。
 */
@Setter
@Getter
public class LoginRequest {

    /** 登录时由服务层 trim 并转为小写的用户名。 */
    @NotBlank
    private String username;

    /** 原始密码，不做 trim；不得记录到日志或放入响应。 */
    @NotBlank
    private String password;

}
