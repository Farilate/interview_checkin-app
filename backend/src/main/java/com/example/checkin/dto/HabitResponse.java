package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * 打卡项响应对象。
 *
 * <p>只返回允许客户端看到的打卡项信息，
 * 不暴露 userId 等内部关联字段。
 */
@Getter
@AllArgsConstructor
public class HabitResponse {

    /** 十进制字符串主键，避免 H5 的 JavaScript Number 丢失 BIGINT 精度。 */
    private final String id;

    /** 打卡项名称。 */
    private final String name;

    /** 打卡项描述，可为空。 */
    private final String description;

    /** 创建时间，精度与 DATETIME(3) 对齐到毫秒，JSON 使用带 Z 的 ISO 8601 UTC 格式。 */
    private final Instant createdAt;

    /** 最后更新时间，与创建时间使用相同的 UTC 序列化契约。 */
    private final Instant updatedAt;
}
