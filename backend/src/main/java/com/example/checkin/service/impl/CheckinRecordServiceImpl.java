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
import java.util.List;

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

    /**
     * 查询当前连续打卡天数。
     *
     * <p>今天已经打卡时从今天开始向前计算；
     * 今天尚未打卡时允许从昨天开始计算。
     */
    @Override
    public int getCurrentStreak(long userId, long habitId) {

        // 同时确认习惯存在且属于当前用户。
        if (habitMapper.findByUserIdAndId(userId, habitId) == null) {
            throw new BusinessException(
                    ErrorCode.HABIT_NOT_FOUND
            );
        }

        // 只读取一次业务时钟，确定当前业务日期。
        LocalDate today = businessClock.instant()
                .atZone(businessClock.getZone())
                .toLocalDate();

        List<LocalDate> dates =
                checkinRecordMapper.findDatesThrough(
                        userId,
                        habitId,
                        today
                );

        if (dates.isEmpty()) {
            return 0;
        }

        /*
         * 今天打过卡：从今天开始计算。
         * 今天没打卡：从昨天开始计算。
         */
        LocalDate expected = dates.getFirst().equals(today)
                ? today
                : today.minusDays(1);

        int streak = 0;

        for (LocalDate date : dates) {
            if (!date.equals(expected)) {
                break;
            }

            streak++;
            expected = expected.minusDays(1);
        }

        return streak;
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
            return toResponse(existing, false);
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
            return toResponse(record, true);
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
                throw e;
            }

            return toResponse(existingRecord, false);
        }
    }

    /**
     * 持久化对象转换为对外响应对象。
     */
    private CheckinResponse toResponse(
            CheckinRecord record,
            boolean created) {

        return new CheckinResponse(
                Long.toString(record.getId()),
                Long.toString(record.getHabitId()),
                record.getCheckinDate(),
                record.getCheckedInAt().toInstant(ZoneOffset.UTC),
                created
        );
    }

    @Override
    public boolean hasCheckedInToday(long userId, long habitId) {

        // 同时检查习惯是否存在，以及是否属于当前登录用户。
        if (habitMapper.findByUserIdAndId(userId, habitId) == null) {
            throw new BusinessException(
                    ErrorCode.HABIT_NOT_FOUND
            );
        }

        // 按业务时区确定今天。
        LocalDate today = businessClock.instant()
                .atZone(businessClock.getZone())
                .toLocalDate();

        CheckinRecord record =
                checkinRecordMapper.findByUserHabitAndDate(
                        userId,
                        habitId,
                        today
                );

        return record != null;
    }

}
