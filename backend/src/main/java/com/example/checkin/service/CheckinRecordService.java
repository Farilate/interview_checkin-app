package com.example.checkin.service;

import com.example.checkin.dto.CheckinResponse;

public interface CheckinRecordService {

    CheckinResponse checkIn(long userId, long habitId);

    /**
     * 查询当前连续打卡天数。
     *
     * <p>今天已打卡时从今天开始计算；
     * 今天未打卡时允许从昨天开始计算。
     */
    int getCurrentStreak(long userId, long habitId);

    /**
     * 判断今天是否已经打卡。
     */
    boolean hasCheckedInToday(long userId, long habitId);
}