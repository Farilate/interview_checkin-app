package com.example.checkin.service.impl;

import com.example.checkin.redis.RedisKeys;
import com.example.checkin.service.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 登录 Session 服务实现。
 *
 * <p>负责生成登录 Token、在 Redis 中保存用户 Session、
 * 根据 Token 查询用户 ID，以及删除 Session。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 用于生成不可预测的随机登录 Token。
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Session 有效时间，从配置文件读取。
     */
    private final Duration sessionTtl;

    public SessionServiceImpl(
            StringRedisTemplate redisTemplate,
            @Value("${app.session.ttl-seconds:7200}") long ttlSeconds) {

        this.redisTemplate = redisTemplate;
        this.sessionTtl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 为指定用户创建登录 Session。
     *
     * <p>返回原始 Token 给客户端，
     * Redis 中只保存 Token 的 SHA-256 哈希对应的 Session。
     */
    @Override
    public String createSession(long userId) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String key = RedisKeys.session(sha256(token));

        redisTemplate.opsForValue().set(
                key,
                String.valueOf(userId),
                sessionTtl
        );

        return token;
    }

    /**
     * 根据客户端提交的原始 Token 查询登录用户 ID。
     *
     * @return Session 不存在或已经过期时返回 null
     */
    @Override
    public Long getUserId(String token) {
        String key = RedisKeys.session(sha256(token));

        String userId = redisTemplate.opsForValue().get(key);

        if (userId == null) {
            return null;
        }

        return Long.valueOf(userId);
    }

    /**
     * 删除当前 Token 对应的 Session，用于退出登录。
     */
    @Override
    public void deleteSession(String token) {
        String key = RedisKeys.session(sha256(token));
        redisTemplate.delete(key);
    }

    /**
     * 对原始 Token 进行 SHA-256 哈希，
     * 避免直接把可用于登录的 Token 存入 Redis Key。
     */
    private String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "当前 JVM 不支持 SHA-256",
                    e
            );
        }
    }
}