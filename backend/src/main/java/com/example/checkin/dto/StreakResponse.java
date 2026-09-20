package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 当前连续打卡天数响应。
 */
@Getter
@AllArgsConstructor
public class StreakResponse {

    /**
     * 当前连续打卡天数。
     *
     * <p>今天已打卡时从今天开始计算；
     * 今天未打卡时允许从昨天开始计算。
     */
    private final int streak;
}