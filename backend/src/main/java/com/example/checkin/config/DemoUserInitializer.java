package com.example.checkin.config;

import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * 应用启动时按运行配置初始化演示账户，不提供公开注册接口。
 * <p>只有用户名和密码均非空白时才执行；当前没有额外启用开关或限定运行环境。
 * 已存在同名用户时跳过，不覆盖密码；测试启动完整应用时也应留意该初始化器。
 */
@Component
public class DemoUserInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    /** 注入账户组件及演示凭证；未配置凭证时使用空字符串，禁止在源码中填写真实密码。 */
    public DemoUserInitializer(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo-user.username:}") String username,
            @Value("${app.demo-user.password:}") String password) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    /**
     * 容器启动后创建缺失的演示用户。
     * @param args 应用启动参数，本初始化器从配置属性获取凭证，不读取这些参数
     */
    @Override
    public void run(String... args) {
        if (username.isBlank() || password.isBlank()) {
            return;
        }

        // 与登录查询保持相同的规范化规则，密码保留原始字符。
        String normalizedUsername =
                username.trim().toLowerCase(Locale.ROOT);

        if (userMapper.findByUsername(normalizedUsername) != null) {
            return;
        }

        // 数据库存储 UTC 时间；这里只初始化账户，不涉及打卡业务日期。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        // 先查询再插入仅避免常规重复初始化；并发启动仍由用户名唯一约束兜底。
        userMapper.insert(user);
    }
}
