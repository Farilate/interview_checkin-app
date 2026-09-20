package com.example.checkin.service;

import java.time.LocalDate;

/**
 * 打卡业务缓存服务。
 *
 * <p>null 表示缓存未命中；
 * Redis 故障由实现层内部处理，不能影响 MySQL 主业务。
 */
public interface CheckinCacheService {

    Boolean getToday(
            long userId,
            long habitId,
            LocalDate businessDate
    );

    void putToday(
            long userId,
            long habitId,
            LocalDate businessDate,
            boolean checkedIn
    );

    Integer getStreak(
            long userId,
            long habitId,
            LocalDate businessDate
    );

    void putStreak(
            long userId,
            long habitId,
            LocalDate businessDate,
            int streak
    );

    /**
     * 打卡成功后删除该日期相关业务缓存。
     */
    void evict(
            long userId,
            long habitId,
            LocalDate businessDate
    );
}