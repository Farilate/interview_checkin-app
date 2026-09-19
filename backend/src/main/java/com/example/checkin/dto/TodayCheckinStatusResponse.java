package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 今日打卡状态响应。
 */
@Getter
@AllArgsConstructor
public class TodayCheckinStatusResponse {

    /**
     * 今天是否已经打卡。
     */
    private final boolean checkedIn;
}