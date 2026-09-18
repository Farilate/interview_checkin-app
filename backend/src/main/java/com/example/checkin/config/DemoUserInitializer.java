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

@Component
public class DemoUserInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

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

    @Override
    public void run(String... args) {
        if (username.isBlank() || password.isBlank()) {
            return;
        }

        String normalizedUsername =
                username.trim().toLowerCase(Locale.ROOT);

        if (userMapper.findByUsername(normalizedUsername) != null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        userMapper.insert(user);
    }
}