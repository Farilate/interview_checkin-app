package com.example.checkin.config;

import com.example.checkin.auth.AuthCredentials;
import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 应用启动时按运行配置初始化演示账户，不提供公开注册接口。
 *
 * <p>初始化规则：
 * <ul>
 *     <li>只有用户名和密码均非空白时才执行；</li>
 *     <li>用户名按照登录流程使用相同规则进行规范化；</li>
 *     <li>密码使用 BCrypt 哈希后写入数据库，不保存明文；</li>
 *     <li>同名用户已经存在时直接跳过，不修改已有密码；</li>
 *     <li>多个应用实例同时启动时，最终由数据库用户名唯一约束保证只创建一条记录。</li>
 * </ul>
 *
 * <p>本初始化器没有额外启用开关或环境限制。
 * 测试如果加载完整 Spring Boot 应用，也应注意是否配置了演示账户凭证。
 */
@Component
public class DemoUserInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    /**
     * 注入账户相关组件及演示账户凭证。
     *
     * <p>如果没有配置：
     * <pre>
     * app.demo-user.username
     * app.demo-user.password
     * </pre>
     *
     * 则使用空字符串，初始化器启动后直接跳过。
     *
     * <p>真实密码只能来自运行配置或环境变量，
     * 不允许硬编码在源码中。
     */
    public DemoUserInitializer(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo-user.username:}") String username,
            @Value("${app.demo-user.password:}") String password
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    /**
     * Spring 容器启动完成后创建缺失的演示用户。
     *
     * <p>采用：
     * <pre>
     * 先查询
     *   ↓
     * 不存在则尝试 INSERT
     *   ↓
     * 数据库 UNIQUE 约束作为最终并发兜底
     * </pre>
     *
     * <p>“先查询”只能优化普通情况下的重复初始化，
     * 不能解决两个应用实例同时执行初始化的竞态条件。
     *
     * <p>因此 INSERT 仍可能因为：
     * <pre>
     * UNIQUE uk_users_username(username)
     * </pre>
     *
     * 发生 {@link DuplicateKeyException}。
     *
     * <p>如果冲突发生后重新查询发现目标用户名已经存在，
     * 说明另一个实例刚刚成功完成初始化，此时可以正常结束。
     *
     * <p>如果重新查询仍然不存在，则说明异常并不能被解释为
     * “另一个实例已经创建了目标账户”，必须继续抛出异常，
     * 防止吞掉真正的数据库问题。
     *
     * @param args 应用启动参数；
     *             本初始化器只读取 Spring 配置，不使用这些参数
     */
    @Override
    public void run(String... args) {

        /*
         * 没有配置完整的演示账户凭证时，
         * 不进行任何数据库操作。
         */
        if (username.isBlank() || password.isBlank()) {
            return;
        }

        /*
         * 与正式登录流程保持相同的用户名规范化规则。
         *
         * 密码不 trim：
         * 密码中的空格属于密码本身的一部分。
         */
        String normalizedUsername =
                AuthCredentials.normalize(
                        username,
                        password
                );

        /*
         * 普通启动路径：
         *
         * 如果演示用户已经存在，
         * 直接结束。
         *
         * 这里绝不能重新覆盖 password_hash，
         * 否则每次应用启动都可能悄悄修改账户密码。
         */
        if (userMapper.findByUsername(normalizedUsername) != null) {
            return;
        }

        /*
         * 数据库存储 UTC 时间。
         *
         * 这里初始化的是账户，
         * 不涉及 Asia/Shanghai 的业务打卡日期。
         */
        LocalDateTime now =
                LocalDateTime.now(ZoneOffset.UTC);

        User user = new User();

        user.setUsername(normalizedUsername);

        /*
         * 数据库只能保存 BCrypt 哈希，
         * 绝不能保存配置中的原始明文密码。
         */
        user.setPasswordHash(
                passwordEncoder.encode(password)
        );

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {

            /*
             * 尝试真正创建演示账户。
             *
             * 在单实例场景下一般直接成功。
             *
             * 在多个实例同时启动时，
             * 可能存在以下竞态：
             *
             * A 查询：不存在
             * B 查询：不存在
             * A INSERT：成功
             * B INSERT：用户名唯一约束冲突
             *
             * 数据库 UNIQUE 约束负责最终的数据一致性。
             */
            userMapper.insert(user);

        } catch (DuplicateKeyException exception) {

            /*
             * 不能看到 DuplicateKeyException 就直接吞掉。
             *
             * 重新查询目标用户名：
             *
             * 如果已经存在，
             * 说明大概率是另一个实例抢先完成了创建，
             * 当前实例可以把初始化视为已经完成。
             */
            if (userMapper.findByUsername(normalizedUsername) != null) {
                return;
            }

            /*
             * 如果发生 DuplicateKeyException，
             * 但目标用户名仍不存在，
             * 就不能把异常伪装成正常并发初始化。
             *
             * 保留原异常，让应用启动失败并暴露给服务端日志，
             * 便于发现数据库结构或其他未知问题。
             */
            throw exception;
        }
    }
}