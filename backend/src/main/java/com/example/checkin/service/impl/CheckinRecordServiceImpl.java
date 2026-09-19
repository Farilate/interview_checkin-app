package com.example.checkin.service.impl;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.dto.CheckinResponse;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.mapper.CheckinRecordMapper;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.model.CheckinRecord;
import com.example.checkin.service.CheckinRecordService;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 每日打卡业务实现。
 *
 * <p>业务日期按照 businessClock 的时区确定，
 * 实际打卡时间统一按 UTC 保存。
 */
@Service
public class CheckinRecordServiceImpl
        implements CheckinRecordService {

    private final CheckinRecordMapper checkinRecordMapper;
    private final HabitMapper habitMapper;
    private final Clock businessClock;

    public CheckinRecordServiceImpl(
            CheckinRecordMapper checkinRecordMapper,
            HabitMapper habitMapper,
            Clock businessClock) {
        this.checkinRecordMapper = checkinRecordMapper;
        this.habitMapper = habitMapper;
        this.businessClock = businessClock;
    }

    @Override
    public CheckinResponse checkIn(long userId, long habitId) {

        // 同时检查习惯是否存在，以及是否属于当前登录用户。
        if (habitMapper.findByUserIdAndId(userId, habitId) == null) {
            throw new BusinessException(
                    ErrorCode.HABIT_NOT_FOUND
            );
        }

        // 只读取一次时间，先对齐 DATETIME(3) 毫秒精度，再派生日期和 UTC 时间。
        // 即使后续查询或写入跨过业务午夜，本次请求仍使用同一个瞬间，避免日期错配。
        Instant now = businessClock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDate checkinDate = now.atZone(businessClock.getZone()).toLocalDate();

        // 重复请求直接返回已有记录，使打卡接口具备幂等性。
        CheckinRecord existing =
                checkinRecordMapper.findByUserHabitAndDate(
                        userId,
                        habitId,
                        checkinDate
                );

        if (existing != null) {
            return toResponse(existing);
        }

        CheckinRecord record = new CheckinRecord();
        record.setUserId(userId);
        record.setHabitId(habitId);
        record.setCheckinDate(checkinDate);
        record.setCheckedInAt(
                LocalDateTime.ofInstant(
                        now,
                        ZoneOffset.UTC
                )
        );

        try {
            checkinRecordMapper.insert(record);
            return toResponse(record);
        } catch (DuplicateKeyException e) {

            // 并发请求可能同时通过预查询。以目标三元组的实际记录确认幂等结果，
            // 不解析异常消息、索引名或驱动错误格式；数据库唯一约束仍负责最终去重。
            CheckinRecord existingRecord =
                    checkinRecordMapper.findByUserHabitAndDate(
                            userId,
                            habitId,
                            checkinDate
                    );

            if (existingRecord == null) {
                // 无法确认目标记录已存在，保留并抛出原异常，不伪装成重复成功。
                throw e;
            }

            return toResponse(existingRecord);
        }
    }

    /**
     * 持久化对象转换为对外响应对象。
     */
    private CheckinResponse toResponse(
            CheckinRecord record) {

        return new CheckinResponse(
                Long.toString(record.getId()),
                Long.toString(record.getHabitId()),
                record.getCheckinDate(),
                record.getCheckedInAt()
                        .toInstant(ZoneOffset.UTC)
        );
    }

}
