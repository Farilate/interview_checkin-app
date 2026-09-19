package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 打卡项响应对象。
 *
 * <p>只返回允许客户端看到的打卡项信息，
 * 不暴露 userId 等内部关联字段。
 */
@Getter
@AllArgsConstructor
public class HabitResponse {

    /** 打卡项主键。 */
    private final long id;

    /** 打卡项名称。 */
    private final String name;

    /** 打卡项描述，可为空。 */
    private final String description;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 最后更新时间。 */
    private final LocalDateTime updatedAt;
}