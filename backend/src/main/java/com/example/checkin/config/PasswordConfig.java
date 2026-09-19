package com.example.checkin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 提供登录密码校验组件；演示账户通过独立 SQL 准备，应用启动不创建用户。 */
@Configuration
public class PasswordConfig {

    /**
     * 创建 BCrypt 编码器，登录时通过 matches 验证原始密码与数据库中的哈希。
     * @return 密码编码器，不应通过重新编码后直接比较字符串的方式验证密码
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
