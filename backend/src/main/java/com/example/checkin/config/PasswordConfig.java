package com.example.checkin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 提供密码哈希组件，供演示账户初始化和登录校验共用；认证拦截由 MVC 配置负责。 */
@Configuration
public class PasswordConfig {

    /**
     * 创建 BCrypt 编码器：初始化账户时生成带盐哈希，登录时通过 matches 验证原始密码。
     * @return 密码编码器，不应通过重新编码后直接比较字符串的方式验证密码
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
