package com.example.checkin.service.impl;

import com.example.checkin.redis.RedisKeys;
import com.example.checkin.common.ErrorCode;
import com.example.checkin.exception.BusinessException;
import org.springframework.dao.DataAccessException;
import org.jspecify.annotations.Nullable;
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

    /** 以 Redis 字符串读写用户 ID，不序列化完整用户对象。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 用于生成不可预测的随机登录 Token。
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Session 有效时间，从配置文件读取。
     */
    private final Duration sessionTtl;

    /**
     * 注入 Redis 客户端及固定会话有效期，单位为秒，缺省为 7200。
     * <p>非法有效期在启动时拒绝，避免登录后才发现无法创建会话。
     */
    public SessionServiceImpl(
            StringRedisTemplate redisTemplate,
            @Value("${app.session.ttl-seconds:7200}") long ttlSeconds) {

        if (ttlSeconds <= 0 || ttlSeconds > Long.MAX_VALUE / 1000) {
            throw new IllegalArgumentException("会话有效期须为正数且可转换为毫秒");
        }
        this.redisTemplate = redisTemplate;
        this.sessionTtl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 为指定用户创建登录 Session。
     *
     * <p>返回原始 Token 给客户端，
     * Redis 中只保存 Token 的 SHA-256 哈希对应的 Session。
     * @param userId 已通过密码认证的数据库用户 ID
     * @return 可供客户端后续放入 Authorization 请求头的原始令牌
     */
    @Override
    public String createSession(long userId) {
        // 32 个随机字节提供 256 位随机性；无填充的 URL 安全 Base64 编码为 43 个字符。
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String key = RedisKeys.session(sha256(token));

        // 同一次写入设置值和过期时间，值为十进制用户 ID 字符串，读取时不滑动续期。
        try {
            redisTemplate.opsForValue().set(
                    key,
                    String.valueOf(userId),
                    sessionTtl
            );
        } catch (DataAccessException exception) {
            // 在会话存储边界分类，避免 Redis 异常被误判成 MySQL 故障。
            throw new BusinessException(ErrorCode.REDIS_SESSION_UNAVAILABLE);
        }

        return token;
    }

    /**
     * 根据客户端提交的原始 Token 查询登录用户 ID。
     *
     * @param token 客户端持有的原始令牌
     * @return Session 不存在或已经过期时返回 null
     */
    @Override
    public @Nullable Long getUserId(String token) {
        String key = RedisKeys.session(sha256(token));

        String userId;
        try {
            userId = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.REDIS_SESSION_UNAVAILABLE);
        }

        if (userId == null) {
            return null;
        }

        // 损坏或非正数身份不允许通过认证，撤销该会话后按未登录处理。
        try {
            long parsed = Long.parseLong(userId);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 不回显 Redis 原值，统一清理无效会话。
        }
        deleteSession(token);
        return null;
    }

    /**
     * 删除当前 Token 对应的 Session，用于退出登录。
     * <p>不检查删除数量，键已过期或已删除时也正常完成；Redis 访问失败则向上传递异常。
     * @param token 当前请求使用的原始令牌
     */
    @Override
    public void deleteSession(String token) {
        String key = RedisKeys.session(sha256(token));
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.REDIS_SESSION_UNAVAILABLE);
        }
    }

    /** 与写入 Redis 的 TTL 使用同一个配置值，避免响应有效期与实际有效期分离。 */
    @Override
    public long getTtlSeconds() {
        return sessionTtl.toSeconds();
    }

    /**
     * 对原始 Token 进行 SHA-256 哈希，
     * 避免直接把可用于登录的 Token 存入 Redis Key。
     * @param value 使用 UTF-8 编码的原始令牌
     * @return 64 位小写十六进制摘要，用于定位会话键
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
