package com.example.checkin.mapper;

import com.example.checkin.model.CheckinRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

/**
 * 打卡记录持久层，只负责参数化 SQL，不判断“今天”或计算连续天数。
 * userId 必须来自服务端身份；业务日期和 UTC 时间由后续业务层统一确定。
 */
@Mapper
public interface CheckinRecordMapper {
    /**
     * 插入记录并回填 record.id，返回受影响行数。
     * 同用户、同习惯、同日期的重复写入由 uk_checkin_user_habit_date 拒绝，
     * 不使用 INSERT IGNORE 吞掉错误。异常由调用方按实际原因处理；
     * HTTP 幂等响应和事务处理属于后续业务层的职责。
     */
    @Insert("""
            INSERT INTO checkin_records (user_id, habit_id, checkin_date, checked_in_at)
            VALUES (#{userId}, #{habitId}, #{checkinDate}, #{checkedInAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CheckinRecord record);

    /** 查询指定用户、习惯和日期的唯一记录，没有记录时返回 null。 */
    @Select("""
            SELECT id, user_id, habit_id, checkin_date, checked_in_at FROM checkin_records
            WHERE user_id = #{userId} AND habit_id = #{habitId} AND checkin_date = #{date}
            """)
    CheckinRecord findByUserHabitAndDate(@Param("userId") long userId, @Param("habitId") long habitId,
                                       @Param("date") LocalDate date);

    /**
     * 返回截至 asOfDate（含当天）的所有打卡日期，按日期从新到旧排列。
     * 不截断为固定最近天数，避免后续连续天数计算遗漏较长历史；
     * 这里只提供日期序列，列表长度不能直接当作连续打卡天数。
     */
    @Select("""
            SELECT checkin_date FROM checkin_records
            WHERE user_id = #{userId} AND habit_id = #{habitId} AND checkin_date <= #{asOfDate}
            ORDER BY checkin_date DESC
            """)
    List<LocalDate> findDatesThrough(@Param("userId") long userId, @Param("habitId") long habitId,
                                    @Param("asOfDate") LocalDate asOfDate);
}
