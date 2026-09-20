package com.example.checkin.integration;

import com.example.checkin.dto.CheckinResponse;
import com.example.checkin.service.CheckinRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL 的 Service 并发验收；创建独立测试数据，结束后只清理该用户的数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class CheckinConcurrencyIT {

    @Autowired
    private CheckinRecordService checkinRecordService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 固定业务日期，避免测试本身跨午夜后合法生成两天记录。 */
    @MockitoBean(name = "businessClock")
    private Clock clock;

    @Test
    @DisplayName("10个并发今日打卡时只能创建一条记录")
    void concurrentCheckInShouldCreateOnlyOneRecord() throws Exception {

        int concurrency = 10;
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T10:00:00.123Z"));

        // 每次测试创建独立用户，避免污染现有 demo 数据。
        String username =
                "c" + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 12);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            jdbcTemplate.update("""
                INSERT INTO users (username, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """,
                    username,
                    "test-password-hash",
                    LocalDateTime.now(ZoneOffset.UTC),
                    LocalDateTime.now(ZoneOffset.UTC)
            );

            Long userId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE username = ?",
                    Long.class,
                    username
            );

            jdbcTemplate.update("""
                INSERT INTO habits (user_id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                    userId,
                    "并发测试习惯",
                    "仅用于并发集成测试",
                    LocalDateTime.now(ZoneOffset.UTC),
                    LocalDateTime.now(ZoneOffset.UTC)
            );

            Long habitId = jdbcTemplate.queryForObject(
                    """
                    SELECT id
                    FROM habits
                    WHERE user_id = ? AND name = ?
                    """,
                    Long.class,
                    userId,
                    "并发测试习惯"
            );

            // 10 个线程全部准备好以后，再统一放行。
            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<CheckinResponse>> futures =
                    new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                futures.add(
                        executor.submit(() -> {
                            ready.countDown();

                            // 等待统一起跑信号。
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("并发起跑信号超时");
                            }

                            return checkinRecordService.checkIn(
                                    userId,
                                    habitId
                            );
                        })
                );
            }

            // 等 10 个线程全部进入等待状态。
            assertTrue(ready.await(10, TimeUnit.SECONDS), "工作线程未及时就绪");

            // 同时放行。
            start.countDown();

            List<CheckinResponse> responses =
                    new ArrayList<>();

            for (Future<CheckinResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            long createdCount = responses.stream()
                    .filter(CheckinResponse::isCreated)
                    .count();

            // 只能有一个线程真正创建记录。
            assertEquals(1, createdCount);

            // 10 个请求最终必须拿到同一条打卡记录。
            assertEquals(
                    1,
                    responses.stream()
                            .map(CheckinResponse::getId)
                            .distinct()
                            .count()
            );

            Integer databaseCount =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT COUNT(*)
                            FROM checkin_records
                            WHERE user_id = ?
                              AND habit_id = ?
                            """,
                            Integer.class,
                            userId,
                            habitId
                    );

            // 响应日期、时间和数据库持久化时间也必须一致。
            assertEquals(1, responses.stream().map(CheckinResponse::getCheckedInAt).distinct().count());
            assertTrue(responses.stream().allMatch(r -> r.getCheckinDate().toString().equals("2026-09-19")));
            LocalDateTime storedTime = jdbcTemplate.queryForObject(
                    "SELECT checked_in_at FROM checkin_records WHERE user_id = ? AND habit_id = ?",
                    LocalDateTime.class, userId, habitId);
            assertEquals(responses.getFirst().getCheckedInAt(), storedTime.toInstant(ZoneOffset.UTC));

            // 数据库最终也只能存在一条记录。
            assertEquals(1, databaseCount);

        } finally {
            executor.shutdownNow();

            // 必须先确认工作线程停止，再清理数据，避免删除与仍在执行的写入互相竞争。
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "工作线程尚未停止，保留测试数据供排查");
            // 清理覆盖数据准备阶段失败的情况；所有删除限定本次随机用户名，不影响其他用户。
            jdbcTemplate.update("""
                    DELETE r FROM checkin_records r JOIN users u ON r.user_id = u.id
                    WHERE u.username = ?
                    """, username);
            jdbcTemplate.update("""
                    DELETE h FROM habits h JOIN users u ON h.user_id = u.id
                    WHERE u.username = ?
                    """, username);
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
        }
    }
}
