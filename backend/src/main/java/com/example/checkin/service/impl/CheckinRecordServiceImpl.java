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

import java.sql.SQLException;

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

        // “今天”按照业务时区确定。
        LocalDate checkinDate =
                LocalDate.now(businessClock);

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

        // 数据库存 DATETIME(3)，因此统一截断到毫秒精度。
        Instant now = businessClock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

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

            /*
             * 两个并发请求可能同时判断“今天尚未打卡”，
             * 此时数据库唯一约束负责保证最终只插入一条记录。
             *
             * 只有确认是“同用户 + 同习惯 + 同日期”唯一键冲突时，
             * 才按重复打卡处理，不能吞掉其他数据库唯一键异常。
             */
            if (!isCheckinDateConflict(e)) {
                throw e;
            }

            CheckinRecord existingRecord =
                    checkinRecordMapper.findByUserHabitAndDate(
                            userId,
                            habitId,
                            checkinDate
                    );

            if (existingRecord == null) {
                // 理论上唯一键冲突后应能查到获胜请求插入的记录；
                // 查不到说明出现了其他异常情况，不伪装成正常幂等响应。
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

    /**
     * 判断异常是否确实来自每日打卡唯一约束。
     */
    private boolean isCheckinDateConflict(
            DuplicateKeyException exception) {

        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == 1062
                        && "23000".equals(sqlException.getSQLState())
                        && sqlException.getMessage() != null
                        && sqlException.getMessage()
                        .contains("uk_checkin_user_habit_date")) {
                    return true;
                }
            }

            cause = cause.getCause();
        }

        return false;
    }
}