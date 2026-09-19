package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 每日打卡成功后的响应。
 */
@Getter
@AllArgsConstructor
public class CheckinResponse {

    /** 打卡记录 ID；以字符串返回，避免前端 JavaScript 大整数精度问题。 */
    private final String id;

    /** 对应的打卡项 ID。 */
    private final String habitId;

    /** 业务日期，例如 2026-09-19。 */
    private final LocalDate checkinDate;

    /** 实际打卡时间，使用 UTC 时间。 */
    private final Instant checkedInAt;
}