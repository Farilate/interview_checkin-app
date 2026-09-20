package com.example.contract;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.config.ApiResponseAdvice;
import com.example.checkin.config.WebMvcConfig;
import com.example.checkin.controller.HabitController;
import com.example.checkin.dto.CurrentUserResponse;
import com.example.checkin.exception.GlobalExceptionHandler;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.mapper.CheckinRecordMapper;
import com.example.checkin.model.CheckinRecord;
import com.example.checkin.service.impl.CheckinRecordServiceImpl;
import com.example.checkin.service.CheckinCacheService;
import java.time.Clock;
import java.time.ZoneId;
import java.time.LocalDate;
import com.example.checkin.model.Habit;
import com.example.checkin.service.AuthService;
import com.example.checkin.service.SessionService;
import com.example.checkin.service.impl.HabitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Habit 真实 HTTP 回归：复用生产 Controller、Service、鉴权注册、校验与 JSON 配置。
 * 仅替换 Mapper 和认证依赖，不连接真实存储；SQL 排序与并发唯一性另由集成验收确认。
 * 测试位于应用扫描包外，避免测试 Bean 混入生产或 PersistenceIT 上下文。
 */
@SpringBootTest(classes = HabitContractTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HabitContractTest {
    @LocalServerPort int port;
    @Autowired HabitMapper habits;
    @Autowired CheckinRecordMapper records;
    @Autowired Clock clock;
    @Autowired SessionService sessions;
    @Autowired AuthService auth;
    @Autowired CheckinCacheService cache;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** 关闭数据源自动配置，显式装配当前 Habit 请求实际经过的生产组件。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({HabitController.class, HabitServiceImpl.class, CheckinRecordServiceImpl.class, WebMvcConfig.class,
            AuthInterceptor.class, ApiResponseAdvice.class, GlobalExceptionHandler.class})
    static class Application {
        /** 固定时钟仅替代系统时间，打卡业务使用生产 Service。 */
        @Bean Clock clock() { return mock(Clock.class); }
        @Bean CheckinRecordMapper records() { return mock(CheckinRecordMapper.class); }
        @Bean HabitMapper habits() { return mock(HabitMapper.class); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean
        CheckinCacheService cache() {
            return mock(CheckinCacheService.class);
        }
    }

    /** 每例清除共享 Bean 的桩与调用记录；两个令牌分别对应两个服务端身份。 */
    @BeforeEach
    void setUp() {
        reset(habits, records, clock, sessions, auth, cache);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T16:00:00.123456789Z"));
        when(sessions.getUserId("expired")).thenReturn(null);
        when(sessions.getUserId("owner")).thenReturn(7L);
        when(sessions.getUserId("other")).thenReturn(8L);
        when(auth.getCurrentUser(7L)).thenReturn(new CurrentUserResponse("7", "owner"));
        when(auth.getCurrentUser(8L)).thenReturn(new CurrentUserResponse("8", "other"));
        when(habits.insert(any())).thenAnswer(call -> {
            Habit habit = call.getArgument(0);
            habit.setId(101L);
            return 1;
        });
        // Contract Test 默认模拟缓存未命中，让请求继续走原有数据库 Mock 逻辑。
        when(cache.getToday(
                anyLong(),
                anyLong(),
                any(LocalDate.class)
        )).thenReturn(null);

        when(cache.getStreak(
                anyLong(),
                anyLong(),
                any(LocalDate.class)
        )).thenReturn(null);
    }

    /** 创建由会话确定所属用户；保存 trim 后名称及 UTC 时间，不向外暴露 userId。 */
    @Test
    void createUsesAuthenticatedOwnerAndUtcTime() throws Exception {
        // 下界也按毫秒比较，避免同一毫秒内的精度截断导致错误地判定时间倒退。
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        JsonNode data = check(send("POST", "?userId=999", "owner",
                "{\"name\":\"  每日阅读  \",\"description\":\"阅读二十分钟\"}"), 201, 0).get("data");
        assertEquals("101", data.get("id").asString());
        assertEquals("每日阅读", data.get("name").asString());
        assertEquals("阅读二十分钟", data.get("description").asString());
        assertFalse(data.has("userId"));
        var saved = org.mockito.ArgumentCaptor.forClass(Habit.class);
        verify(habits).insert(saved.capture());
        Habit habit = saved.getValue();
        assertEquals(7L, habit.getUserId());
        assertEquals("每日阅读", habit.getName());
        assertFalse(habit.getCreatedAt().isBefore(before));
        assertFalse(habit.getCreatedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        assertEquals(habit.getCreatedAt(), habit.getUpdatedAt());
        // 捕获实际传入 Mapper 的时间，确保写入和响应均不含亚毫秒数据。
        assertEquals(0, habit.getCreatedAt().getNano() % 1_000_000);
        assertTrue(data.get("createdAt").asString().endsWith("Z"));
        assertEquals(habit.getCreatedAt().toInstant(ZoneOffset.UTC), Instant.parse(data.get("createdAt").asString()));
        assertEquals(data.get("createdAt"), data.get("updatedAt"));
        verify(habits).findByUserIdAndName(7L, "每日阅读");
    }

    /** 名称 trim 后按 Unicode 码点计长；50 个 emoji 和 200 个描述 emoji 均可保存。 */
    @Test
    void acceptsLengthBoundaries() throws Exception {
        check(send("POST", "", "owner", json.writeValueAsString(
                Map.of("name", "  " + "📚".repeat(50) + "  ", "description", "📚".repeat(200)))), 201, 0);
        verify(habits).insert(any());
    }

    /** 缺省、显式 null、空字符串及纯空白描述均保存并返回 null。 */
    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"阅读\"}", "{\"name\":\"阅读\",\"description\":null}",
            "{\"name\":\"阅读\",\"description\":\"\"}", "{\"name\":\"阅读\",\"description\":\"   \"}"})
    void optionalDescription(String body) throws Exception {
        JsonNode data = check(send("POST", "", "owner", body), 201, 0).get("data");
        assertTrue(data.has("description"));
        assertTrue(data.get("description").isNull());
        verify(habits).insert(argThat(h -> h.getDescription() == null));
    }

    /** 非法 JSON、空白名称及服务端专属字段在业务写入前拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
            "{\"name\":\"阅读\",\"userId\":8}", "{\"name\":\"阅读\",\"id\":1}",
            "{\"name\":\"阅读\"} {}", "{"})
    void invalidCreateDoesNotAccessMapper(String body) throws Exception {
        check(send("POST", "", "owner", body), 400, 40001);
        verifyNoInteractions(habits);
    }

    /** 名称 trim 后超过 50 码点、描述超过 200 码点时，在任何 Mapper 调用前拒绝。 */
    @Test
    void rejectsOverlongCodePointFields() throws Exception {
        for (Map<String, String> body : List.of(Map.of("name", "文".repeat(51)),
                Map.of("name", " " + "文".repeat(51)), Map.of("name", "📚".repeat(51)),
                Map.of("name", "阅读", "description", "📚".repeat(201)),
                Map.of("name", "阅读", "description", "文".repeat(201)))) {
            check(send("POST", "", "owner", json.writeValueAsString(body)), 400, 40001);
        }
        verifyNoInteractions(habits);
    }

    /** 预检查发现同用户同名时直接冲突，不执行 INSERT。 */
    @Test
    void duplicatePrecheckDoesNotInsert() throws Exception {
        when(habits.findByUserIdAndName(7L, "阅读")).thenReturn(new Habit());
        check(send("POST", "", "owner", "{\"name\":\" 阅读 \"}"), 409, 40901);
        verify(habits, never()).insert(any());
    }

    /** 模拟预检查后发生唯一键竞争；验证异常分支，不冒充真实并发数据库测试。 */
    @ParameterizedTest
    @ValueSource(strings = {"uk_habits_user_name", "habits.uk_habits_user_name", "checkin.habits.uk_habits_user_name"})
    void duplicateInsertIsConflict(String key) throws Exception {
        doThrow(new DuplicateKeyException("包装消息", new SQLException(
                "Duplicate entry '7-阅读' for key '" + key + "'", "23000", 1062)))
                .when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 409, 40901);
        verify(habits).insert(any());
    }

    /** 不能因主键、相似索引名或重复值中含有目标索引名称而误报业务重名。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "Duplicate entry '7' for key 'PRIMARY'",
            "Duplicate entry '7' for key 'habits.uk_habits_user_id_id'",
            "Duplicate entry '7' for key 'uk_habits_user_name_backup'",
            "Duplicate entry 'uk_habits_user_name' for key 'PRIMARY'",
            "Duplicate entry 'for key 'uk_habits_user_name'' for key 'PRIMARY'",
            "unknown format uk_habits_user_name"})
    void unrelatedOrUnknownUniqueConflictIsInternalError(String message) throws Exception {
        doThrow(new DuplicateKeyException("包装消息", new SQLException(message, "23000", 1062)))
                .when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 500, 50001);
    }

    /** 只有名字相符还不够：缺少 JDBC 原因、错误号或 SQLState 不符均保守回退。 */
    @Test
    void requiresMysqlDuplicateErrorIdentity() throws Exception {
        String message = "Duplicate entry '7' for key 'uk_habits_user_name'";
        for (DuplicateKeyException failure : List.of(
                new DuplicateKeyException(message),
                new DuplicateKeyException("包装消息", new SQLException(message, "23000", 1452)),
                new DuplicateKeyException("包装消息", new SQLException(message, "HY000", 1062)),
                new DuplicateKeyException("包装消息", new SQLException(null, "23000", 1062)))) {
            doThrow(failure).when(habits).insert(any());
            check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 500, 50001);
        }
    }

    /** 有内容的描述不擅自 trim，保留用户输入中的排版空格。 */
    @Test
    void preservesNonBlankDescription() throws Exception {
        JsonNode data = check(send("POST", "", "owner",
                "{\"name\":\"阅读\",\"description\":\"  保留空格  \"}"), 201, 0).get("data");
        assertEquals("  保留空格  ", data.get("description").asString());
        verify(habits).insert(argThat(h -> "  保留空格  ".equals(h.getDescription())));
    }

    /** 相同名称分别按两名用户查询和插入，不能错误地实施全局名称唯一。 */
    @Test
    void differentUsersMayCreateSameName() throws Exception {
        for (String token : List.of("owner", "other")) {
            check(send("POST", "", token, "{\"name\":\"阅读\"}"), 201, 0);
        }
        verify(habits).findByUserIdAndName(7L, "阅读");
        verify(habits).findByUserIdAndName(8L, "阅读");
        verify(habits).insert(argThat(h -> h.getUserId() == 7L));
        verify(habits).insert(argThat(h -> h.getUserId() == 8L));
    }

    /** 默认分页及空集合均为正常结果；伪造查询身份不能改变 Mapper 的用户条件。 */
    @Test
    void defaultEmptyPageUsesSessionUser() throws Exception {
        JsonNode data = check(send("GET", "?userId=999", "owner", null), 200, 0).get("data");
        assertEquals(0, data.get("items").size());
        assertEquals(0, data.get("total").asLong());
        assertEquals(1, data.get("page").asInt());
        assertEquals(20, data.get("pageSize").asInt());
        verify(habits).findByUserId(7L, 0L, 20);
        verify(habits).countByUserId(7L);
    }

    /** 列表映射保持 Mapper 顺序，包含更新时间，且响应不泄露持有者字段。 */
    @Test
    void mapsPageItemsAndTotal() throws Exception {
        Habit habit = new Habit();
        habit.setId(9007199254740993L);
        habit.setUserId(8L);
        habit.setName("运动");
        habit.setCreatedAt(LocalDateTime.of(2026, 9, 19, 1, 0));
        habit.setUpdatedAt(habit.getCreatedAt());
        when(habits.findByUserId(8L, 2L, 2)).thenReturn(List.of(habit));
        when(habits.countByUserId(8L)).thenReturn(3L);
        JsonNode data = check(send("GET", "?page=2&pageSize=2", "other", null), 200, 0).get("data");
        assertEquals(3, data.get("total").asLong());
        assertEquals(2, data.get("page").asInt());
        assertEquals(2, data.get("pageSize").asInt());
        assertEquals(1, data.get("items").size());
        assertEquals("运动", data.get("items").get(0).get("name").asString());
        assertEquals("9007199254740993", data.get("items").get(0).get("id").asString());
        assertEquals("2026-09-19T01:00:00Z", data.get("items").get(0).get("createdAt").asString());
        assertTrue(data.get("items").get(0).has("updatedAt"));
        assertFalse(data.get("items").get(0).has("userId"));
        verify(habits).countByUserId(8L);
        verify(habits).findByUserId(8L, 2L, 2);
    }

    /** 最大合法页码不会发生 int 乘法溢出；越过末页仍返回用户总数。 */
    @Test
    void largePageKeepsLongOffsetAndTotal() throws Exception {
        when(habits.countByUserId(7L)).thenReturn(3L);
        JsonNode data = check(send("GET", "?page=2147483647&pageSize=100", "owner", null), 200, 0).get("data");
        assertEquals(0, data.get("items").size());
        assertEquals(3, data.get("total").asLong());
        verify(habits).findByUserId(7L, 214748364600L, 100);
    }

    /** 负数、零、超限、溢出及无法绑定的分页参数都应返回 400，不能查询数据库。 */
    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=-1", "page=abc", "page=2147483648",
            "pageSize=0", "pageSize=-1", "pageSize=101", "pageSize=abc"})
    void invalidPagination(String query) throws Exception {
        check(send("GET", "?" + query, "owner", null), 400, 40001);
        verifyNoInteractions(habits);
    }

    /** 创建和列表均受生产拦截器保护，未认证时业务 Mapper 不应被调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void requiresAuthentication(String method) throws Exception {
        for (String token : new String[]{null, "expired"}) {
            check(send(method, "", token, method.equals("POST") ? "{\"name\":\"阅读\"}" : null), 401, 40101);
        }
        verifyNoInteractions(habits);
    }

    /** 插入阶段的数据库故障不能变成重名；对外隐藏原始数据库消息。 */
    @Test
    void insertFailuresKeepInfrastructureClassification() throws Exception {
        doThrow(new DataAccessResourceFailureException("private-db-detail")).when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 503, 50302);
        doThrow(new DataIntegrityViolationException("private-db-detail")).when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 500, 50001);
    }

    /** 分页查询或总数查询失败均不能返回不完整的成功分页。 */
    @Test
    void pageFailuresDoNotReturnPartialSuccess() throws Exception {
        when(habits.findByUserId(7L, 0L, 20)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("GET", "", "owner", null), 503, 50302);
        doReturn(List.of()).when(habits).findByUserId(7L, 0L, 20);
        when(habits.countByUserId(7L)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("GET", "", "owner", null), 503, 50302);
    }

    /** 首次打卡使用会话身份、上海业务日期和 UTC 毫秒时间，忽略客户端伪造身份与日期。 */
    @Test
    void firstCheckinUsesServerIdentityAndBusinessDate() throws Exception {
        allowCheckin();
        JsonNode data = check(send("PUT", "/101/checkins/today?userId=8&date=2000-01-01", "owner",
                "{\"userId\":8,\"checkinDate\":\"2000-01-01\"}"), 200, 0).get("data");
        assertEquals("9007199254740993", data.get("id").asString());
        assertEquals("101", data.get("habitId").asString());
        assertEquals("2026-09-20", data.get("checkinDate").asString());
        assertEquals("2026-09-19T16:00:00.123Z", data.get("checkedInAt").asString());
        assertEquals(5, data.size());
        assertTrue(data.get("created").asBoolean());
        verify(records).insert(argThat(r -> r.getUserId() == 7L && r.getHabitId() == 101L
                && r.getCheckinDate().equals(LocalDate.of(2026, 9, 20))
                && r.getCheckedInAt().getNano() == 123_000_000));
    }

    /** 顺序重复请求返回同一记录与原始时间，只允许首次调用写入。 */
    @Test
    void repeatedCheckinReturnsStoredRecord() throws Exception {
        allowCheckin();
        CheckinRecord[] saved = new CheckinRecord[1];
        when(records.findByUserHabitAndDate(7L, 101L, LocalDate.of(2026, 9, 20)))
                .thenAnswer(call -> saved[0]);
        doAnswer(call -> {
            saved[0] = call.getArgument(0);
            saved[0].setId(501L);
            return 1;
        }).when(records).insert(any());
        JsonNode first = check(send("PUT", "/101/checkins/today", "owner", null), 200, 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T17:00:00Z"));
        JsonNode second = check(send("PUT", "/101/checkins/today", "owner", null), 200, 0);
        assertTrue(first.get("data").get("created").asBoolean());
        assertFalse(second.get("data").get("created").asBoolean());
        for (String field : List.of("id", "habitId", "checkinDate", "checkedInAt")) {
            assertEquals(first.get("data").get(field), second.get("data").get(field));
        }
        verify(records, times(1)).insert(any());
    }

    /** 冲突后读取获胜记录；仅模拟数据库竞争分支，不代表已验证真实并发。 */
    @ParameterizedTest
    @ValueSource(strings = {"uk_checkin_user_habit_date", "checkin_records.uk_checkin_user_habit_date"})
    void checkinConflictReturnsWinner(String key) throws Exception {
        allowCheckin();
        CheckinRecord winner = storedCheckin();
        when(records.findByUserHabitAndDate(7L, 101L, winner.getCheckinDate())).thenReturn(null, winner);
        doThrow(checkinDuplicate(key)).when(records).insert(any());
        JsonNode data = check(send("PUT", "/101/checkins/today", "owner", null), 200, 0).get("data");
        assertEquals("501", data.get("id").asString());
        assertFalse(data.get("created").asBoolean());
        assertEquals("2026-09-19T16:00:00Z", data.get("checkedInAt").asString());
        verify(records, times(2)).findByUserHabitAndDate(7L, 101L, winner.getCheckinDate());
    }

    /** 即使发生重复键异常，回查不到目标记录也不能伪装成功。 */
    @Test
    void checkinConflictWithoutWinnerIsInternalError() throws Exception {
        allowCheckin();
        doThrow(checkinDuplicate("uk_checkin_user_habit_date")).when(records).insert(any());
        check(send("PUT", "/101/checkins/today", "owner", null), 500, 50001);
    }

    /** 重复键异常的格式不影响判断；目标记录不存在时各种消息都必须继续报错。 */
    @Test
    void checkinUnrelatedFailuresRemainErrors() throws Exception {
        allowCheckin();
        for (DuplicateKeyException failure : List.of(checkinDuplicate("PRIMARY"),
                new DuplicateKeyException("uk_checkin_user_habit_date"),
                new DuplicateKeyException("包装", new SQLException("uk_checkin_user_habit_date", "23000", 1452)),
                new DuplicateKeyException("包装", new SQLException("uk_checkin_user_habit_date", "HY000", 1062)))) {
            doThrow(failure).when(records).insert(any());
            check(send("PUT", "/101/checkins/today", "owner", null), 500, 50001);
        }
    }

    /** 合法正整数对应的习惯不存在时返回 40401，不能读取或写入打卡记录。 */
    @ParameterizedTest
    @ValueSource(strings = {"101"})
    void checkinMissingHabitDoesNotTouchRecords(String id) throws Exception {
        check(send("PUT", "/" + id + "/checkins/today", "owner", null), 404, 40401);
        verifyNoInteractions(records);
    }

    /** 另一会话不能借请求参数操作用户一的习惯。 */
    @Test
    void checkinRejectsAnotherOwner() throws Exception {
        allowCheckin();
        check(send("PUT", "/101/checkins/today?userId=7", "other", null), 404, 40401);
        verify(habits).findByUserIdAndId(8L, 101L);
        verifyNoInteractions(records);
    }

    /** 认证与路径绑定失败必须发生在业务访问前。 */
    @Test
    void checkinAuthenticationAndMalformedId() throws Exception {
        check(send("PUT", "/101/checkins/today", null, null), 401, 40101);
        check(send("PUT", "/101/checkins/today", "expired", null), 401, 40101);
        for (String id : List.of("abc", "9223372036854775808")) {
            check(send("PUT", "/" + id + "/checkins/today", "owner", null), 400, 40001);
        }
        verifyNoInteractions(habits, records);
    }

    /** 所有数据库阶段失败都不能返回虚假的打卡成功。 */
    @Test
    void checkinStorageFailures() throws Exception {
        allowCheckin();
        doThrow(new DataAccessResourceFailureException("private-db-detail")).when(records).insert(any());
        check(send("PUT", "/101/checkins/today", "owner", null), 503, 50302);
        doThrow(new DataIntegrityViolationException("private-db-detail")).when(records).insert(any());
        check(send("PUT", "/101/checkins/today", "owner", null), 500, 50001);
        when(records.findByUserHabitAndDate(anyLong(), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("PUT", "/101/checkins/today", "owner", null), 503, 50302);
        when(habits.findByUserIdAndId(7L, 101L))
                .thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("PUT", "/101/checkins/today", "owner", null), 503, 50302);
    }

    /** 固定在午夜两侧分别发起请求，验证跨日、跨月、跨年和闰日的业务日期。 */
    @ParameterizedTest
    @ValueSource(strings = {"2026-09-19T15:59:59.999Z", "2026-09-19T16:00:00Z",
            "2026-09-30T16:00:00Z", "2026-12-31T16:00:00Z", "2028-02-28T16:00:00Z"})
    void checkinCalendarBoundaries(String instant) throws Exception {
        allowCheckin();
        Instant time = Instant.parse(instant);
        when(clock.instant()).thenReturn(time);
        JsonNode data = check(send("PUT", "/101/checkins/today", "owner", null), 200, 0).get("data");
        assertEquals(time.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString(),
                data.get("checkinDate").asString());
        assertEquals(time, Instant.parse(data.get("checkedInAt").asString()));
    }

    /** 打卡仅接受新 PUT 路径；旧 POST 集合路径已移除，不能继续调用。 */
    @Test
    void checkinMethodContract() throws Exception {
        check(send("POST", "/101/checkins/today", "owner", null), 405, 40500);
        check(send("POST", "/101/checkins", "owner", null), 404, 40400);
    }

    /** 今日状态只有 checkedIn 字段，无记录是正常 false，写入后重新查询为 true。 */
    @Test
    void todayStatusBeforeAndAfterCheckin() throws Exception {
        allowCheckin();
        CheckinRecord[] saved = new CheckinRecord[1];
        when(records.findByUserHabitAndDate(7L, 101L, LocalDate.of(2026, 9, 20)))
                .thenAnswer(call -> saved[0]);
        doAnswer(call -> {
            saved[0] = call.getArgument(0);
            saved[0].setId(501L);
            return 1;
        }).when(records).insert(any());
        JsonNode before = check(send("GET", "/101/checkins/today", "owner", null), 200, 0).get("data");
        assertEquals(1, before.size());
        assertFalse(before.get("checkedIn").asBoolean());
        check(send("PUT", "/101/checkins/today", "owner", null), 200, 0);
        JsonNode after = check(send("GET", "/101/checkins/today", "owner", null), 200, 0).get("data");
        assertEquals(1, after.size());
        assertTrue(after.get("checkedIn").asBoolean());
        verify(records, times(1)).insert(any());
    }

    /** 查询只用当前业务日期，跨午夜后不能将昨天记录当作今日已打卡。 */
    @Test
    void todayStatusChangesAtBusinessMidnight() throws Exception {
        allowCheckin();
        when(records.findByUserHabitAndDate(7L, 101L, LocalDate.of(2026, 9, 19))).thenReturn(storedCheckin());
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T15:59:59.999Z"));
        assertTrue(check(send("GET", "/101/checkins/today", "owner", null), 200, 0)
                .get("data").get("checkedIn").asBoolean());
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T16:00:00Z"));
        assertFalse(check(send("GET", "/101/checkins/today", "owner", null), 200, 0)
                .get("data").get("checkedIn").asBoolean());
        verify(records).findByUserHabitAndDate(7L, 101L, LocalDate.of(2026, 9, 20));
        verify(records, never()).insert(any());
    }

    /** 连续天数响应按当前 DTO 仅包含 streak，空历史为 0，昨天锚点有效。 */
    @Test
    void streakHttpResponseMatchesCurrentDto() throws Exception {
        allowCheckin();
        JsonNode empty = check(send("GET", "/101/streak", "owner", null), 200, 0).get("data");
        assertEquals(1, empty.size());
        assertEquals(0, empty.get("streak").asInt());
        when(records.findDatesThrough(7L, 101L, LocalDate.of(2026, 9, 20)))
                .thenReturn(List.of(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 18)));
        assertEquals(2, check(send("GET", "/101/streak", "owner", null), 200, 0)
                .get("data").get("streak").asInt());
        verify(records, never()).insert(any());
    }

    /** 两个 GET 均需要认证；合法 ID 对应他人或不存在习惯时返回 40401。 */
    @ParameterizedTest
    @ValueSource(strings = {"/checkins/today", "/streak"})
    void checkinQueriesEnforceAuthenticationAndOwnership(String suffix) throws Exception {
        allowCheckin();
        check(send("GET", "/101" + suffix, null, null), 401, 40101);
        check(send("GET", "/101" + suffix, "expired", null), 401, 40101);
        check(send("GET", "/101" + suffix + "?userId=7", "other", null), 404, 40401);
        for (String id : List.of("999")) {
            check(send("GET", "/" + id + suffix, "owner", null), 404, 40401);
        }
        for (String id : List.of("abc", "9223372036854775808")) {
            check(send("GET", "/" + id + suffix, "owner", null), 400, 40001);
        }
        verifyNoInteractions(records);
    }

    /** 请求体与查询参数不能伪造查询身份或业务日期。 */
    @ParameterizedTest
    @ValueSource(strings = {"/checkins/today", "/streak"})
    void checkinQueriesIgnoreClientDateAndIdentity(String suffix) throws Exception {
        allowCheckin();
        check(send("GET", "/101" + suffix + "?userId=8&date=2000-01-01", "owner",
                "{\"userId\":8,\"date\":\"2000-01-01\"}"), 200, 0);
        if (suffix.equals("/streak")) {
            verify(records).findDatesThrough(7L, 101L, LocalDate.of(2026, 9, 20));
        } else {
            verify(records).findByUserHabitAndDate(7L, 101L, LocalDate.of(2026, 9, 20));
        }
        verify(clock, times(1)).instant();
    }

    /** 查询阶段数据库故障不能包装成 false/0 的正常业务结果。 */
    @ParameterizedTest
    @ValueSource(strings = {"/checkins/today", "/streak"})
    void checkinQueriesPropagateDatabaseFailures(String suffix) throws Exception {
        allowCheckin();
        when(records.findByUserHabitAndDate(anyLong(), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        when(records.findDatesThrough(anyLong(), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("GET", "/101" + suffix, "owner", null), 503, 50302);
        when(habits.findByUserIdAndId(7L, 101L)).thenThrow(new DataIntegrityViolationException("private-db-detail"));
        check(send("GET", "/101" + suffix, "owner", null), 500, 50001);
    }

    /** 三个路径接口均在访问业务 Mapper 前拒绝 0 和负数，区分参数错误与资源不存在。 */
    @ParameterizedTest
    @CsvSource({
            "PUT,/checkins/today,0", "PUT,/checkins/today,-1",
            "GET,/checkins/today,0", "GET,/checkins/today,-1",
            "GET,/streak,0", "GET,/streak,-1"
    })
    void nonPositiveHabitIdIsBadRequest(String method, String suffix, String id) throws Exception {
        check(send(method, "/" + id + suffix, "owner", null), 400, 40001);
        verifyNoInteractions(habits, records);
    }

    /** false 与 0 均为缓存命中，仍先验证归属，但无需查询打卡记录。 */
    @Test
    void cachedNegativeResultsAvoidRecordQueries() throws Exception {
        allowCheckin();
        LocalDate day = LocalDate.of(2026, 9, 20);
        when(cache.getToday(7L, 101L, day)).thenReturn(false);
        when(cache.getStreak(7L, 101L, day)).thenReturn(0);
        assertFalse(check(send("GET", "/101/checkins/today", "owner", null), 200, 0)
                .get("data").get("checkedIn").asBoolean());
        assertEquals(0, check(send("GET", "/101/streak", "owner", null), 200, 0)
                .get("data").get("streak").asInt());
        verifyNoInteractions(records);
        verify(habits, times(2)).findByUserIdAndId(7L, 101L);
    }

    /** 已有缓存也不能绕过习惯归属检查。 */
    @Test
    void ownershipIsCheckedBeforeCacheAccess() throws Exception {
        check(send("GET", "/101/checkins/today", "other", null), 404, 40401);
        check(send("GET", "/101/streak", "other", null), 404, 40401);
        verifyNoInteractions(cache, records);
    }

    /** 缓存 Miss 时返回 MySQL 结果并回填，包括 false 和 0。 */
    @Test
    void missesPopulateBothNegativeCaches() throws Exception {
        allowCheckin();
        LocalDate day = LocalDate.of(2026, 9, 20);
        check(send("GET", "/101/checkins/today", "owner", null), 200, 0);
        check(send("GET", "/101/streak", "owner", null), 200, 0);
        verify(cache).putToday(7L, 101L, day, false);
        verify(cache).putStreak(7L, 101L, day, 0);
    }

    /** 首次成功与顺序重复均主动失效本日两个查询缓存。 */
    @Test
    void successfulAndRepeatedCheckinEvictCache() throws Exception {
        allowCheckin();
        LocalDate day = LocalDate.of(2026, 9, 20);
        check(send("PUT", "/101/checkins/today", "owner", null), 200, 0);
        when(records.findByUserHabitAndDate(7L, 101L, day)).thenReturn(storedCheckin());
        check(send("PUT", "/101/checkins/today", "owner", null), 200, 0);
        verify(cache, times(2)).evict(7L, 101L, day);
    }

    /** 构造当前用户拥有的习惯，并模拟数据库主键回填。 */
    private void allowCheckin() {
        when(habits.findByUserIdAndId(7L, 101L)).thenReturn(new Habit());
        when(records.insert(any())).thenAnswer(call -> {
            CheckinRecord record = call.getArgument(0);
            record.setId(9007199254740993L);
            return 1;
        });
    }

    /** 模拟另一个请求已经写入数据库的获胜记录。 */
    private CheckinRecord storedCheckin() {
        CheckinRecord record = new CheckinRecord();
        record.setId(501L);
        record.setUserId(7L);
        record.setHabitId(101L);
        record.setCheckinDate(LocalDate.of(2026, 9, 20));
        record.setCheckedInAt(LocalDateTime.of(2026, 9, 19, 16, 0));
        return record;
    }

    /** 使用 JDBC 错误号与状态模拟 MySQL 唯一键冲突，不连接真实数据库。 */
    private DuplicateKeyException checkinDuplicate(String key) {
        return new DuplicateKeyException("包装消息", new SQLException(
                "Duplicate entry '7-101-2026-09-20' for key '" + key + "'", "23000", 1062));
    }

    /** 通过随机端口发送请求，让真实 MVC 完成绑定、校验、序列化和错误处理。 */
    private HttpResponse<String> send(String method, String query, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/habits" + query))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 所有分支共同检查统一结构、禁止 HTTP 缓存及错误信息脱敏。 */
    private JsonNode check(HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode root = json.readTree(response.body());
        assertEquals(code, root.get("code").asInt());
        assertTrue(root.has("message"));
        assertTrue(root.has("data"));
        if (code != 0) assertTrue(root.get("data").isNull());
        assertFalse(response.body().contains("private-db-detail"));
        return root;
    }
}
