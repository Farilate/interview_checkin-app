package com.example.checkin;

import com.example.checkin.mapper.CheckinRecordMapper;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.model.CheckinRecord;
import com.example.checkin.model.Habit;
import com.example.checkin.service.impl.CheckinRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 打卡 Service 定向回归：验证单次取时及异常对象原样传播，不替代真实数据库并发测试。 */
class CheckinRecordServiceTest {
    private final CheckinRecordMapper records = mock(CheckinRecordMapper.class);
    private final HabitMapper habits = mock(HabitMapper.class);
    private final Clock clock = mock(Clock.class);
    private final CheckinRecordServiceImpl service = new CheckinRecordServiceImpl(records, habits, clock);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 19);

    /** 第一瞬间位于上海午夜前，若错误地再次取时则会读到下一天。 */
    @BeforeEach
    void setUp() {
        when(habits.findByUserIdAndId(7L, 101L)).thenReturn(new Habit());
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T15:59:59.999999999Z"),
                Instant.parse("2026-09-19T16:00:00Z"));
    }

    /** 插入和响应必须使用午夜前的同一瞬间，且实际只调用一次 instant()。 */
    @Test
    void midnightUsesOneInstantForDateAndTimestamp() {
        when(records.insert(any())).thenAnswer(call -> {
            CheckinRecord record = call.getArgument(0);
            record.setId(501L);
            assertEquals(DATE, record.getCheckinDate());
            assertEquals(LocalDateTime.of(2026, 9, 19, 15, 59, 59, 999_000_000), record.getCheckedInAt());
            return 1;
        });
        var result = service.checkIn(7L, 101L);
        assertEquals(DATE, result.getCheckinDate());
        assertTrue(result.isCreated());
        assertEquals(Instant.parse("2026-09-19T15:59:59.999Z"), result.getCheckedInAt());
        assertEquals(result.getCheckinDate(), result.getCheckedInAt().atZone(clock.getZone()).toLocalDate());
        verify(clock, times(1)).instant();
        verify(records).findByUserHabitAndDate(7L, 101L, DATE);
    }

    /** 无 JDBC 原因、无索引名也应根据目标记录判断成功，并保持获胜记录的原始时间。 */
    @Test
    void duplicateWithWinnerReturnsStoredRecord() {
        CheckinRecord winner = new CheckinRecord();
        winner.setId(501L);
        winner.setHabitId(101L);
        winner.setUserId(7L);
        winner.setCheckinDate(DATE);
        winner.setCheckedInAt(LocalDateTime.of(2026, 9, 19, 15, 0));
        when(records.findByUserHabitAndDate(7L, 101L, DATE)).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("与索引格式无关的消息")).when(records).insert(any());
        var result = service.checkIn(7L, 101L);
        assertFalse(result.isCreated());
        assertEquals("501", result.getId());
        assertEquals(DATE, result.getCheckinDate());
        assertEquals(winner.getCheckedInAt().toInstant(ZoneOffset.UTC), result.getCheckedInAt());
        verify(records, times(2)).findByUserHabitAndDate(7L, 101L, DATE);
        verify(clock, times(1)).instant();
    }

    /** 回查为空必须抛出捕获到的同一异常对象，保留原始异常及其诊断信息。 */
    @Test
    void duplicateWithoutWinnerRethrowsOriginalException() {
        DuplicateKeyException original = new DuplicateKeyException("原始异常");
        doThrow(original).when(records).insert(any());
        assertSame(original, assertThrows(DuplicateKeyException.class, () -> service.checkIn(7L, 101L)));
        verify(records, times(2)).findByUserHabitAndDate(7L, 101L, DATE);
        verify(clock, times(1)).instant();
    }

    /** 连续天数按日历日期递减，既不能用总条数代替，也不能固定截断最近记录。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("streakCases")
    void streakCalendarCases(String scenario, LocalDate today, List<LocalDate> history, int expected) {
        when(clock.instant()).thenReturn(today.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        when(records.findDatesThrough(7L, 101L, today)).thenReturn(history);
        assertEquals(expected, service.getCurrentStreak(7L, 101L), scenario);
        verify(records).findDatesThrough(7L, 101L, today);
        verify(clock, times(1)).instant();
        verify(records, never()).insert(any());
    }

    /** Mapper 约定只返回截至当天、按日期倒序且不重复的记录。 */
    private static Stream<Arguments> streakCases() {
        LocalDate day = LocalDate.of(2026, 9, 19);
        return Stream.of(
                Arguments.of("空历史", day, List.of(), 0),
                Arguments.of("只有今天", day, List.of(day), 1),
                Arguments.of("只有昨天", day, List.of(day.minusDays(1)), 1),
                Arguments.of("最新记录为前天", day, List.of(day.minusDays(2), day.minusDays(3)), 0),
                Arguments.of("今天连续三天", day, List.of(day, day.minusDays(1), day.minusDays(2)), 3),
                Arguments.of("昨天锚点连续三天", day, List.of(day.minusDays(1), day.minusDays(2), day.minusDays(3)), 3),
                Arguments.of("昨天漏打", day, List.of(day, day.minusDays(2), day.minusDays(3)), 1),
                Arguments.of("中间断签后旧记录不计", day, List.of(day, day.minusDays(1), day.minusDays(3)), 2),
                Arguments.of("跨月", LocalDate.of(2026, 10, 1),
                        List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 29)), 3),
                Arguments.of("跨年", LocalDate.of(2027, 1, 1),
                        List.of(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31)), 2),
                Arguments.of("闰年二月", LocalDate.of(2028, 3, 1),
                        List.of(LocalDate.of(2028, 3, 1), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 2, 28)), 3),
                Arguments.of("平年二月", LocalDate.of(2026, 3, 1),
                        List.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 28)), 2),
                Arguments.of("长序列无固定天数截断", day,
                        IntStream.range(0, 400).mapToObj(day::minusDays).toList(), 400));
    }

    /** 同一份历史在上海午夜前后切换锚点：先保留昨天连续，隔天仍未打卡则清零。 */
    @Test
    void streakExpiresAfterMissingWholeBusinessDay() {
        when(records.findDatesThrough(eq(7L), eq(101L), any()))
                .thenReturn(List.of(DATE.minusDays(1), DATE.minusDays(2)));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T15:59:59.999Z"),
                Instant.parse("2026-09-19T16:00:00Z"));
        assertEquals(2, service.getCurrentStreak(7L, 101L));
        assertEquals(0, service.getCurrentStreak(7L, 101L));
        verify(clock, times(2)).instant();
    }
}
