package com.example.checkin;

import com.example.checkin.service.impl.CheckinCacheServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.*;

class CheckinCacheServiceImplTest {

    private final StringRedisTemplate redisTemplate =
            mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations =
            mock(ValueOperations.class);


    /**
     * Redis 删除业务缓存失败时，
     * evict 必须吞掉缓存异常，不能向上影响已经成功的 MySQL 业务。
     */
    @Test
    void evictFailureDoesNotBreakBusiness() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-20T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );

        CheckinCacheServiceImpl service =
                new CheckinCacheServiceImpl(
                        redisTemplate,
                        clock,
                        600
                );

        LocalDate date = LocalDate.of(2026, 9, 20);

        // 模拟 Redis 批量删除两个缓存键时不可用。
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .when(redisTemplate)
                .delete(List.of("checkin:v1:today:7:101:2026-09-20",
                        "checkin:v1:streak:7:101:2026-09-20"));

        // Redis 缓存删除失败不能传播到业务层。
        assertDoesNotThrow(() ->
                service.evict(
                        7L,
                        101L,
                        date
                )
        );
    }

    @Test
    void evictDeletesTodayAndStreakCache() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-20T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );

        CheckinCacheServiceImpl service =
                new CheckinCacheServiceImpl(
                        redisTemplate,
                        clock,
                        600
                );

        LocalDate date = LocalDate.of(2026, 9, 20);

        service.evict(7L, 101L, date);

        verify(redisTemplate).delete(List.of(
                "checkin:v1:today:7:101:2026-09-20",
                "checkin:v1:streak:7:101:2026-09-20"));
    }

    @Test
    void redisWriteFailureDoesNotBreakBusiness() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-20T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .when(valueOperations)
                .set(
                        "checkin:v1:today:7:101:2026-09-20",
                        "1",
                        Duration.ofSeconds(600)
                );

        CheckinCacheServiceImpl service =
                new CheckinCacheServiceImpl(
                        redisTemplate,
                        clock,
                        600
                );

        // 缓存写失败不能向上抛异常。
        assertDoesNotThrow(() ->
                service.putToday(
                        7L,
                        101L,
                        LocalDate.of(2026, 9, 20),
                        true
                )
        );
    }

    @Test
    void redisReadFailureIsTreatedAsCacheMiss() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-20T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(
                "checkin:v1:today:7:101:2026-09-20"
        )).thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        CheckinCacheServiceImpl service =
                new CheckinCacheServiceImpl(
                        redisTemplate,
                        clock,
                        600
                );

        Boolean result = service.getToday(
                7L,
                101L,
                LocalDate.of(2026, 9, 20)
        );

        // Redis 读取失败等同于 Cache Miss，上层会继续查询 MySQL。
        assertNull(result);
    }

    @Test
    void cacheUsesConfiguredTtlWhenFarFromMidnight() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-20T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        CheckinCacheServiceImpl service =
                new CheckinCacheServiceImpl(
                        redisTemplate,
                        clock,
                        600
                );

        service.putToday(
                7L,
                101L,
                LocalDate.of(2026, 9, 20),
                true
        );

        verify(valueOperations).set(
                "checkin:v1:today:7:101:2026-09-20",
                "1",
                Duration.ofSeconds(600)
        );
    }

    @Test
    void cacheTtlDoesNotCrossBusinessMidnight() {
        // 北京时间 2026-09-20 23:59:50，距离零点只剩 10 秒。
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-20T15:59:50Z"),
                ZoneId.of("Asia/Shanghai")
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        CheckinCacheServiceImpl service =
                new CheckinCacheServiceImpl(
                        redisTemplate,
                        clock,
                        600
                );

        service.putToday(
                7L,
                101L,
                LocalDate.of(2026, 9, 20),
                true
        );

        verify(valueOperations).set(
                "checkin:v1:today:7:101:2026-09-20",
                "1",
                Duration.ofSeconds(10)
        );
    }
}