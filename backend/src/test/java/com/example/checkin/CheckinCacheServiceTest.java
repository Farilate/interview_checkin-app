package com.example.checkin;

import com.example.checkin.redis.RedisKeys;
import com.example.checkin.service.impl.CheckinCacheServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 生产缓存实现回归，替换 Redis 网络访问；不把模拟 TTL 等同于真实自然到期验收。 */
class CheckinCacheServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final LocalDate date = LocalDate.of(2026, 9, 20);
    private CheckinCacheServiceImpl cache;

    /** 默认在上海中午执行，距离午夜大于配置 TTL。 */
    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = at("2026-09-20T04:00:00Z", 600);
    }

    /** 显式指定业务时钟，精确验证传给 Redis 的 TTL。 */
    private CheckinCacheServiceImpl at(String instant, long ttl) {
        return new CheckinCacheServiceImpl(redis,
                Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")), ttl);
    }

    /** 键必须包含用途、用户、习惯和业务日期。 */
    @Test
    void keyNamespacesAndIsolation() {
        assertEquals("checkin:v1:today:7:101:2026-09-20", RedisKeys.today(7, 101, date));
        assertEquals("checkin:v1:streak:7:101:2026-09-20", RedisKeys.streak(7, 101, date));
        assertNotEquals(RedisKeys.today(7, 101, date), RedisKeys.today(8, 101, date));
        assertNotEquals(RedisKeys.today(7, 101, date), RedisKeys.today(7, 102, date));
        assertNotEquals(RedisKeys.today(7, 101, date), RedisKeys.today(7, 101, date.plusDays(1)));
    }

    /** 空键返回 null，false 和 0 则是合法命中；读取不续期。 */
    @Test
    void missesAndValidValues() {
        String today = RedisKeys.today(7, 101, date);
        String streak = RedisKeys.streak(7, 101, date);
        when(values.get(today)).thenReturn(null, "0", "1");
        when(values.get(streak)).thenReturn(null, "0", "12");
        assertNull(cache.getToday(7, 101, date));
        assertEquals(false, cache.getToday(7, 101, date));
        assertEquals(true, cache.getToday(7, 101, date));
        assertNull(cache.getStreak(7, 101, date));
        assertEquals(0, cache.getStreak(7, 101, date));
        assertEquals(12, cache.getStreak(7, 101, date));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 布尔与数字按字符串写入，空结果也缓存，使用同一配置 TTL。 */
    @Test
    void writesIncludeNegativeResultsAndTtl() {
        cache.putToday(7, 101, date, false);
        cache.putToday(7, 101, date, true);
        cache.putStreak(7, 101, date, 0);
        cache.putStreak(7, 101, date, 12);
        verify(values).set(RedisKeys.today(7, 101, date), "0", Duration.ofSeconds(600));
        verify(values).set(RedisKeys.today(7, 101, date), "1", Duration.ofSeconds(600));
        verify(values).set(RedisKeys.streak(7, 101, date), "0", Duration.ofSeconds(600));
        verify(values).set(RedisKeys.streak(7, 101, date), "12", Duration.ofSeconds(600));
    }

    /** 午夜前只剩 250 毫秒时不得继续使用完整 600 秒 TTL。 */
    @Test
    void ttlStopsAtBusinessMidnight() {
        cache = at("2026-09-20T15:59:59.750Z", 600);
        cache.putToday(7, 101, date, true);
        cache.putStreak(7, 101, date, 3);
        verify(values).set(RedisKeys.today(7, 101, date), "1", Duration.ofMillis(250));
        verify(values).set(RedisKeys.streak(7, 101, date), "3", Duration.ofMillis(250));
    }

    /** 计算期间跨过午夜时，不再回填昨天的缓存。 */
    @Test
    void expiredDateDoesNotWrite() {
        cache = at("2026-09-20T16:00:00Z", 600);
        cache.putToday(7, 101, date, true);
        cache.putStreak(7, 101, date, 3);
        verifyNoInteractions(values);
    }

    /** 非正数 TTL 在 Bean 构造阶段立即失败，而不是静默跳过写入。 */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveTtlFailsConstruction(long ttl) {
        assertThrows(IllegalArgumentException.class, () -> at("2026-09-20T04:00:00Z", ttl));
        verifyNoInteractions(values);
    }

    /** 不可解析或溢出的连续天数视为未命中。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "bad", "2147483648"})
    void malformedStreakFallsBack(String value) {
        when(values.get(anyString())).thenReturn(value);
        assertNull(cache.getStreak(7, 101, date));
    }

    /** 读写 Redis 故障不向调用方传播，读视为 Miss，写忽略失败。 */
    @Test
    void redisReadAndWriteFailuresAreBestEffort() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("测试故障"));
        assertNull(cache.getToday(7, 101, date));
        assertNull(cache.getStreak(7, 101, date));
        doThrow(new RedisConnectionFailureException("测试故障"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        assertDoesNotThrow(() -> cache.putToday(7, 101, date, true));
        assertDoesNotThrow(() -> cache.putStreak(7, 101, date, 3));
    }

    /** 正常删除同时覆盖 today 和 streak，且不删除其他日期或其他用户的键。 */
    @Test
    void evictsBothKeys() {
        cache.evict(7, 101, date);
        verify(redis).delete(List.of(RedisKeys.today(7, 101, date), RedisKeys.streak(7, 101, date)));
        verifyNoMoreInteractions(redis);
    }

    /** 损坏今日值及负数连续天数必须视为 Miss，不能成为业务结果。 */
    @Test
    void invalidValuesAreCacheMisses() {
        when(values.get(RedisKeys.today(7, 101, date))).thenReturn("broken");
        when(values.get(RedisKeys.streak(7, 101, date))).thenReturn("-3");
        assertNull(cache.getToday(7, 101, date));
        assertNull(cache.getStreak(7, 101, date));
    }

    /** 批量删除失败不传播异常，两个键必须在同一次调用中传入。 */
    @Test
    void batchEvictionFailureDoesNotPropagate() {
        when(redis.delete(List.of(RedisKeys.today(7, 101, date), RedisKeys.streak(7, 101, date))))
                .thenThrow(new RedisConnectionFailureException("测试故障"));
        assertDoesNotThrow(() -> cache.evict(7, 101, date));
        verify(redis).delete(List.of(RedisKeys.today(7, 101, date), RedisKeys.streak(7, 101, date)));
        verify(redis, never()).delete(anyString());
    }

    /** 午夜前不足 1ms 不发 SET，恰好 1ms 则允许写入。 */
    @Test
    void millisecondThreshold() {
        cache = at("2026-09-20T15:59:59.999500Z", 30);
        cache.putToday(7, 101, date, true);
        cache.putStreak(7, 101, date, 2);
        verifyNoInteractions(values);
        cache = at("2026-09-20T15:59:59.999Z", 30);
        cache.putToday(7, 101, date, true);
        cache.putStreak(7, 101, date, 2);
        verify(values).set(RedisKeys.today(7, 101, date), "1", Duration.ofMillis(1));
        verify(values).set(RedisKeys.streak(7, 101, date), "2", Duration.ofMillis(1));
    }

    /** 负数不能写入缓存；正数配置正常工作。 */
    @Test
    void negativeStreakIsNotWritten() {
        cache.putStreak(7, 101, date, -1);
        verifyNoInteractions(values);
        cache = at("2026-09-20T04:00:00Z", 30);
        cache.putStreak(7, 101, date, 2);
        verify(values).set(RedisKeys.streak(7, 101, date), "2", Duration.ofSeconds(30));
    }

    /** 生产缓存与生产业务 Service 组合：损坏值回源并以数据库结果覆盖。 */
    @Test
    void corruptedValuesFallBackToDatabaseAndRepopulate() {
        var habits = mock(com.example.checkin.mapper.HabitMapper.class);
        var records = mock(com.example.checkin.mapper.CheckinRecordMapper.class);
        when(habits.findByUserIdAndId(7, 101)).thenReturn(new com.example.checkin.model.Habit());
        when(values.get(RedisKeys.today(7, 101, date))).thenReturn("broken");
        when(values.get(RedisKeys.streak(7, 101, date))).thenReturn("-3");
        when(records.findByUserHabitAndDate(7, 101, date)).thenReturn(new com.example.checkin.model.CheckinRecord());
        when(records.findDatesThrough(7, 101, date)).thenReturn(List.of(date, date.minusDays(1)));
        var service = new com.example.checkin.service.impl.CheckinRecordServiceImpl(records, habits,
                Clock.fixed(Instant.parse("2026-09-20T04:00:00Z"), ZoneId.of("Asia/Shanghai")), cache);
        assertTrue(service.hasCheckedInToday(7, 101));
        assertEquals(2, service.getCurrentStreak(7, 101));
        verify(records).findByUserHabitAndDate(7, 101, date);
        verify(records).findDatesThrough(7, 101, date);
        verify(values).set(RedisKeys.today(7, 101, date), "1", Duration.ofSeconds(600));
        verify(values).set(RedisKeys.streak(7, 101, date), "2", Duration.ofSeconds(600));
    }
}
