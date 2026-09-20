package com.example.checkin.service.impl;

import com.example.checkin.redis.RedisKeys;
import com.example.checkin.service.CheckinCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 打卡业务缓存实现。
 *
 * <p>业务缓存不是主数据源。
 * Redis 故障时直接视为缓存不可用，让上层继续访问 MySQL。
 */
@Service
public class CheckinCacheServiceImpl implements CheckinCacheService {

    private static final Logger log =
            LoggerFactory.getLogger(CheckinCacheServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock businessClock;
    private final Duration configuredTtl;

    public CheckinCacheServiceImpl(
            StringRedisTemplate redisTemplate,
            Clock businessClock,
            @Value("${app.cache.ttl-seconds:600}") long ttlSeconds) {

        this.redisTemplate = redisTemplate;
        this.businessClock = businessClock;
        this.configuredTtl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public Boolean getToday(
            long userId,
            long habitId,
            LocalDate businessDate) {

        try {
            String value = redisTemplate.opsForValue().get(
                    RedisKeys.today(userId, habitId, businessDate)
            );

            if (value == null) {
                return null;
            }

            return "1".equals(value);

        } catch (DataAccessException e) {
            log.warn("读取今日打卡缓存失败，回退 MySQL");
            return null;
        }
    }

    @Override
    public void putToday(
            long userId,
            long habitId,
            LocalDate businessDate,
            boolean checkedIn) {

        Duration ttl = calculateTtl(businessDate);

        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.today(userId, habitId, businessDate),
                    checkedIn ? "1" : "0",
                    ttl
            );
        } catch (DataAccessException e) {
            log.warn("写入今日打卡缓存失败，忽略缓存异常");
        }
    }

    @Override
    public Integer getStreak(
            long userId,
            long habitId,
            LocalDate businessDate) {

        try {
            String value = redisTemplate.opsForValue().get(
                    RedisKeys.streak(userId, habitId, businessDate)
            );

            if (value == null) {
                return null;
            }

            return Integer.valueOf(value);

        } catch (DataAccessException | NumberFormatException e) {
            log.warn("读取连续打卡缓存失败，回退 MySQL");
            return null;
        }
    }

    @Override
    public void putStreak(
            long userId,
            long habitId,
            LocalDate businessDate,
            int streak) {

        Duration ttl = calculateTtl(businessDate);

        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.streak(userId, habitId, businessDate),
                    Integer.toString(streak),
                    ttl
            );
        } catch (DataAccessException e) {
            log.warn("写入连续打卡缓存失败，忽略缓存异常");
        }
    }

    @Override
    public void evict(
            long userId,
            long habitId,
            LocalDate businessDate) {

        try {
            redisTemplate.delete(
                    RedisKeys.today(userId, habitId, businessDate)
            );

            redisTemplate.delete(
                    RedisKeys.streak(userId, habitId, businessDate)
            );

        } catch (DataAccessException e) {
            log.warn("删除打卡业务缓存失败，忽略缓存异常");
        }
    }

    /**
     * 实际 TTL 不能超过配置值，也不能跨过下一个业务日零点。
     */
    private Duration calculateTtl(LocalDate businessDate) {

        Instant now = businessClock.instant();
        ZoneId zone = businessClock.getZone();

        Instant nextMidnight = businessDate
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant();

        Duration untilMidnight =
                Duration.between(now, nextMidnight);

        if (untilMidnight.isZero() || untilMidnight.isNegative()) {
            return Duration.ZERO;
        }

        return configuredTtl.compareTo(untilMidnight) <= 0
                ? configuredTtl
                : untilMidnight;
    }
}