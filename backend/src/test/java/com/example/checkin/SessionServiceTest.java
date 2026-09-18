package com.example.checkin;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.service.impl.SessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 会话存储边界回归：验证键、固定 TTL、损坏数据以及读写删除故障分类。 */
class SessionServiceTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SessionServiceImpl service;

    /** 每个测例独立替身，避免会话或故障设置相互影响。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new SessionServiceImpl(redis, 123);
    }

    /** 原始令牌不出现在 Redis 键中，值与 TTL 同次写入；读取不产生续期写操作。 */
    @Test
    void sessionLifecycle() throws Exception {
        String token = service.createSession(7L);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        String expectedKey = "checkin:v1:session:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        verify(values).set(expectedKey, "7", Duration.ofSeconds(123));
        assertEquals(123, service.getTtlSeconds());
        when(values.get(expectedKey)).thenReturn("7").thenReturn(null);
        assertEquals(7L, service.getUserId(token));
        assertNull(service.getUserId(token));
        service.deleteSession(token);
        verify(redis).delete(expectedKey);
        verify(values, times(1)).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    /** 无效身份应清除并返回未登录，而不是以 500 或合法用户身份继续。 */
    @ParameterizedTest
    @ValueSource(strings = {"bad", "0", "-1", "9223372036854775808", ""})
    void corruptValueIsRevoked(String value) {
        when(values.get(anyString())).thenReturn(value);
        assertNull(service.getUserId("token"));
        var key = ArgumentCaptor.forClass(String.class);
        verify(values).get(key.capture());
        verify(redis).delete(key.getValue());
    }

    /** Redis 读、写、删除均需分类为会话不可用，禁止误报数据库故障或返回成功。 */
    @ParameterizedTest
    @ValueSource(strings = {"read", "write", "delete"})
    void redisFailures(String operation) {
        var failure = new RedisConnectionFailureException("测试连接故障");
        switch (operation) {
            case "read" -> when(values.get(anyString())).thenThrow(failure);
            case "write" -> doThrow(failure).when(values).set(anyString(), anyString(), any(Duration.class));
            case "delete" -> when(redis.delete(anyString())).thenThrow(failure);
            default -> fail("未知测试操作");
        }
        var exception = assertThrows(BusinessException.class, () -> {
            switch (operation) {
                case "read" -> service.getUserId("token");
                case "write" -> service.createSession(7L);
                case "delete" -> service.deleteSession("token");
                default -> fail("未知测试操作");
            }
        });
        assertEquals(ErrorCode.REDIS_SESSION_UNAVAILABLE, exception.getErrorCode());
    }

    /** 有效期配置错误必须在创建服务时失败，而非等到用户登录。 */
    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MAX_VALUE})
    void invalidTtl(long ttl) {
        assertThrows(IllegalArgumentException.class, () -> new SessionServiceImpl(redis, ttl));
    }
}
