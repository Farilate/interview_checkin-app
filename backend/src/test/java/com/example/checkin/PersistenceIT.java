package com.example.checkin;

import com.example.checkin.mapper.CheckinRecordMapper;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.CheckinRecord;
import com.example.checkin.model.Habit;
import com.example.checkin.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2：持久层集成测试。
 * <p>本测试使用真实的 Spring Boot 容器、MyBatis Mapper 和本地 MySQL，
 * 用来验证当前项目持久层是否能够按照 database/init.sql 中定义的表结构正常工作。
 * <p>本测试主要验证：
 * <ul>
 *     <li>UserMapper 的插入和查询是否正确；</li>
 *     <li>HabitMapper 的插入、分页、用户隔离是否正确；</li>
 *     <li>CheckinRecordMapper 的插入、查询和历史日期查询是否正确；</li>
 *     <li>数据库 UNIQUE 约束是否真正生效；</li>
 *     <li>数据库 FOREIGN KEY 约束是否真正生效；</li>
 *     <li>MyBatis 的字段映射、主键回填是否正确。</li>
 * </ul>
 * <p>本测试不负责创建数据库和数据表。
 * 运行测试之前，backend/config/application-local.yml 指向的专用测试库中必须已经存在：
 * <ul>
 *     <li>users</li>
 *     <li>habits</li>
 *     <li>checkin_records</li>
 * </ul>
 * <p>{@link ActiveProfiles} 激活 local profile，因此测试直接使用：
 * <pre>
 * application.yml
 *       +
 * ./config/application-local.yml（工作目录为 backend）
 * </pre>
 * 中配置的数据库。
 * <p>整个测试类使用 {@link Transactional}。
 * Spring 会为每一个测试方法开启事务；
 * {@link Rollback} 保证测试结束以后事务自动回滚。
 * <p>因此测试过程中插入的数据是真实写入 MySQL 事务的，
 * Mapper 可以正常查询到，但测试结束以后不会永久保留在数据库。
 * <p>注意：
 * 本类不进行真正的多线程并发测试。
 * 因为测试事务绑定当前测试线程，新线程不会自动加入同一个事务。
 * 并发打卡测试应单独放到 CheckinConcurrencyIT 中实现。
 * <p>本类只验证持久层，不代表 Controller、登录鉴权、Redis、
 * HTTP 幂等接口、连续打卡算法等功能已经实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Transactional
@Rollback
class PersistenceIT {

    /**
     * 固定测试时间。
     * <p>测试不使用 LocalDateTime.now()，
     * 防止测试结果受到当前系统时间影响。
     * <p>123_000_000 纳秒 = 123 毫秒，
     * 与 MySQL DATETIME(3) 的毫秒精度对应。
     * <p>项目约定 DATETIME 中保存的是应用提供的 UTC 时间；
     * LocalDateTime 本身不携带时区，这里只测试数据库字段读写是否一致。
     */
    private static final LocalDateTime UTC_TIME =
            LocalDateTime.of(
                    2026,
                    9,
                    18,
                    1,
                    2,
                    3,
                    123_000_000
            );

    /**
     * JdbcTemplate 用于直接查询数据库。
     * <p>它不通过 MyBatis Mapper，
     * 因此适合在部分测试中独立核对 Mapper 最终写入的数据库结果。
     */
    @Autowired
    JdbcTemplate jdbc;

    /**
     * UserMapper 的真实 MyBatis 代理对象。
     * <p>这里没有使用 Mock，
     * 调用 insert / findById 等方法时会真正执行 SQL。
     */
    @Autowired
    UserMapper users;

    /**
     * HabitMapper 的真实 MyBatis 代理对象。
     */
    @Autowired
    HabitMapper habits;

    /**
     * CheckinRecordMapper 的真实 MyBatis 代理对象。
     */
    @Autowired
    CheckinRecordMapper records;


    // ========================================================================
    // UserMapper 测试
    // ========================================================================

    /**
     * 验证用户最基本的完整持久化流程：
     * <pre>
     * Java User 对象
     *      ↓
     * INSERT
     *      ↓
     * MySQL users
     *      ↓
     * SELECT
     *      ↓
     * Java User 对象
     * </pre>
     * 同时验证：
     * <ol>
     *     <li>AUTO_INCREMENT 主键能否回填到 Java 对象；</li>
     *     <li>findById 是否正确；</li>
     *     <li>findByUsername 是否正确；</li>
     *     <li>所有字段是否正确映射；</li>
     *     <li>不存在的数据是否返回 null；</li>
     *     <li>username 的 ascii_bin 大小写敏感行为；</li>
     *     <li>MyBatis 参数绑定不会把 SQL 字符串当作 SQL 代码执行。</li>
     * </ol>
     */
    @Test
    @DisplayName("用户：主键回填、完整字段映射、精确查询与空结果")
    void userMapperRoundTrip() {

        // 创建并插入一个测试用户。
        User saved = user("user_roundtrip");

        // 使用 MyBatis useGeneratedKeys 后，
        // INSERT 完成应该自动把数据库生成的 id 回填到 saved.id。
        assertNotNull(saved.getId());

        // 分别通过主键和用户名查询同一个用户。
        User byId = users.findById(saved.getId());
        User byUsername = users.findByUsername(saved.getUsername());

        assertNotNull(byId);
        assertNotNull(byUsername);

        // 两种查询方式拿到的字段都应该和原对象一致。
        for (User loaded : List.of(byId, byUsername)) {

            assertEquals(
                    saved.getId(),
                    loaded.getId()
            );

            assertEquals(
                    saved.getUsername(),
                    loaded.getUsername()
            );

            assertEquals(
                    saved.getPasswordHash(),
                    loaded.getPasswordHash()
            );

            assertEquals(
                    UTC_TIME,
                    loaded.getCreatedAt()
            );

            assertEquals(
                    saved.getUpdatedAt(),
                    loaded.getUpdatedAt()
            );
        }

        // 一个理论上不存在的主键应该查询不到。
        assertNull(
                users.findById(Long.MAX_VALUE)
        );

        // 一个不存在的用户名也应该查询不到。
        assertNull(
                users.findByUsername("user_missing")
        );

        /*
         * users.username 使用 ascii_bin。
         * 因此 Mapper 直接查询时：
         * user_roundtrip
         * 和：
         * USER_ROUNDTRIP
         * 被数据库视为不同字符串。
         * 业务层以后会负责把用户名规范化为小写，
         * Mapper 层本身不负责这种规范化。
         */
        assertNull(
                users.findByUsername("USER_ROUNDTRIP")
        );

        /*
         * 检查 MyBatis 是否正确使用参数绑定。
         * 这一串字符必须被数据库当作普通 username，
         * 而不是拼接为 SQL。
         */
        assertNull(
                users.findByUsername("' OR 1=1 --")
        );
    }

    /**
     * 验证 users.username 的数据库唯一约束。
     * <p>数据库中存在：
     * <pre>
     * UNIQUE KEY uk_users_username (username)
     * </pre>
     * 因此同一个用户名只能存在一条。
     */
    @Test
    @DisplayName("用户：重复用户名被唯一约束拒绝")
    void userMapperRejectsDuplicateUsername() {

        // 第一次插入应该成功。
        User first = user("user_duplicate");

        /*
         * 构造第二个相同 username 的 User。
         * 不直接调用 user()，
         * 是因为这里我们就是要显式测试 insert 失败。
         */
        User duplicate = newUser("user_duplicate");

        // 第二次插入应该被数据库 UNIQUE 约束拒绝。
        DuplicateKeyException error =
                assertThrows(
                        DuplicateKeyException.class,
                        () -> users.insert(duplicate)
                );

        /*
         * 不仅检查发生了 DuplicateKeyException，
         * 还检查真正触发的是预期的唯一约束。
         */
        assertTrue(
                error.getMostSpecificCause()
                        .getMessage()
                        .contains("uk_users_username")
        );

        // 原来的第一条记录仍然应该能够正常查到。
        User loaded =
                users.findByUsername("user_duplicate");

        assertNotNull(loaded);

        assertEquals(
                first.getId(),
                loaded.getId()
        );
    }


    // ========================================================================
    // HabitMapper 测试
    // ========================================================================

    /**
     * 验证习惯的基本插入、主键回填和字段映射。
     * <p>同时检查：
     * <ol>
     *     <li>description = null 可以正常保存；</li>
     *     <li>中文可以正常保存；</li>
     *     <li>emoji 可以正常保存；</li>
     *     <li>单引号可以通过参数绑定安全保存；</li>
     *     <li>created_at / updated_at 映射正确。</li>
     * </ol>
     */
    @Test
    @DisplayName("习惯：插入回填、完整字段映射及可空描述")
    void habitMapperRoundTrip() {

        // Habit 必须属于真实存在的 User。
        User owner = user("habit_roundtrip");

        /*
         * 第一条 Habit 专门验证 description 为 null。
         * 注意现在同一用户的习惯名称必须唯一，
         * 所以不能再让两个 Habit 都叫“阅读📚”。
         */
        Habit nullable = habit(
                owner.getId(),
                "无描述习惯"
        );

        assertNotNull(nullable.getId());

        Habit nullableLoaded =
                habits.findByUserIdAndId(
                        owner.getId(),
                        nullable.getId()
                );

        assertNotNull(nullableLoaded);

        assertNull(
                nullableLoaded.getDescription()
        );

        /*
         * 第二个 Habit 使用另外一个名字，
         * 避免违反：
         * uk_habits_user_name(user_id, name)
         */
        Habit saved = new Habit();

        saved.setUserId(owner.getId());
        saved.setName("阅读📚");
        saved.setDescription("每天阅读 '十页'，记录心得📝");
        saved.setCreatedAt(UTC_TIME);
        saved.setUpdatedAt(UTC_TIME.plusSeconds(1));

        assertEquals(
                1,
                habits.insert(saved)
        );

        // AUTO_INCREMENT 主键应该已经回填。
        assertNotNull(saved.getId());

        Habit loaded =
                habits.findByUserIdAndId(
                        owner.getId(),
                        saved.getId()
                );

        assertNotNull(loaded);

        assertEquals(
                saved.getId(),
                loaded.getId()
        );

        assertEquals(
                owner.getId(),
                loaded.getUserId()
        );

        assertEquals(
                saved.getName(),
                loaded.getName()
        );

        assertEquals(
                saved.getDescription(),
                loaded.getDescription()
        );

        assertEquals(
                saved.getCreatedAt(),
                loaded.getCreatedAt()
        );

        assertEquals(
                saved.getUpdatedAt(),
                loaded.getUpdatedAt()
        );
    }

    /**
     * 验证同一个用户不能创建两个同名习惯。
     * <p>对应数据库约束：
     * <pre>
     * UNIQUE KEY uk_habits_user_name (user_id, name)
     * </pre>
     * 因此：
     * <pre>
     * 用户 A + 每日阅读   -> 成功
     * 用户 A + 每日阅读   -> 失败
     * </pre>
     */
    @Test
    @DisplayName("习惯：同一用户不能创建同名习惯")
    void habitMapperRejectsDuplicateNameForSameUser() {

        User owner =
                user("habit_duplicate_name");

        // 第一个同名习惯正常插入。
        habit(
                owner.getId(),
                "每日阅读"
        );

        // 构造第二个相同 user_id + name 的 Habit。
        Habit duplicate =
                newHabit(
                        owner.getId(),
                        "每日阅读"
                );

        // 数据库应该通过唯一索引拒绝第二次 INSERT。
        DuplicateKeyException error =
                assertThrows(
                        DuplicateKeyException.class,
                        () -> habits.insert(duplicate)
                );

        assertTrue(
                error.getMostSpecificCause()
                        .getMessage()
                        .contains("uk_habits_user_name")
        );
    }

    /**
     * 验证不同用户可以创建相同名字的习惯。
     * <p>因为唯一键是：
     * <pre>
     * (user_id, name)
     * </pre>
     * 而不是：
     * <pre>
     * name
     * </pre>
     * 所以：
     * <pre>
     * 用户 A + 每日阅读   -> 成功
     * 用户 B + 每日阅读   -> 成功
     * </pre>
     */
    @Test
    @DisplayName("习惯：不同用户可以创建同名习惯")
    void habitMapperAllowsSameNameForDifferentUsers() {

        User userA =
                user("habit_same_name_a");

        User userB =
                user("habit_same_name_b");

        Habit habitA =
                habit(
                        userA.getId(),
                        "每日阅读"
                );

        Habit habitB =
                habit(
                        userB.getId(),
                        "每日阅读"
                );

        assertNotNull(habitA.getId());
        assertNotNull(habitB.getId());

        // 同名查询必须同时使用用户条件，分别找到各自的记录，不能串到其他用户。
        assertEquals(habitA.getId(), habits.findByUserIdAndName(userA.getId(), "每日阅读").getId());
        assertEquals(habitB.getId(), habits.findByUserIdAndName(userB.getId(), "每日阅读").getId());
        assertNull(habits.findByUserIdAndName(userA.getId(), "不存在的名称"));
        assertNull(habits.findByUserIdAndName(Long.MAX_VALUE, "每日阅读"));

        // 两条不同的数据库记录应该拥有不同主键。
        assertNotEquals(
                habitA.getId(),
                habitB.getId()
        );
    }

    /**
     * 验证 Habit 分页、稳定排序以及不同用户的数据隔离。
     * <p>预期 Mapper 使用类似：
     * <pre>
     * WHERE user_id = ?
     * ORDER BY created_at DESC, id DESC
     * LIMIT ? OFFSET ?
     * </pre>
     * 的查询规则。
     */
    @Test
    @DisplayName("习惯：分页按创建时间和 ID 倒序，并隔离不同用户")
    void habitMapperPaginationAndOwnership() {

        User owner =
                user("habit_pages");

        User other =
                user("habit_other");

        /*
         * first 和 second 使用完全相同的 created_at。
         * 因此数据库在 created_at 相同时，
         * 应继续使用 id DESC 作为第二排序字段。
         */
        Habit first =
                habit(
                        owner.getId(),
                        "阅读"
                );

        Habit second =
                habit(
                        owner.getId(),
                        "跑步"
                );

        /*
         * older 最后插入，因此它的 id 反而可能最大。
         * 但 created_at 比 first / second 早一天。
         * 这样可以确认 Mapper 真的是：
         * ORDER BY created_at DESC, id DESC
         * 而不是偷懒只按照 id 排序。
         */
        Habit older = new Habit();

        older.setUserId(owner.getId());
        older.setName("早起");
        older.setDescription(null);
        older.setCreatedAt(UTC_TIME.minusDays(1));
        older.setUpdatedAt(UTC_TIME.minusDays(1));

        assertEquals(
                1,
                habits.insert(older)
        );

        assertNotNull(older.getId());

        /*
         * other 是另一个用户。
         * 他可以创建和 owner 相同名字的“阅读”，
         * 因为唯一约束包含 user_id。
         */
        habit(
                other.getId(),
                "阅读"
        );

        // owner 一共拥有三个 Habit。
        assertEquals(
                3,
                habits.countByUserId(owner.getId())
        );

        // other 只拥有一个 Habit。
        assertEquals(
                1,
                habits.countByUserId(other.getId())
        );

        /*
         * 第一页：
         * LIMIT 2 OFFSET 0
         * first 和 second created_at 相同，
         * second 后插入，因此 ID 更大，
         * id DESC 后 second 应该排在 first 前面。
         */
        assertEquals(
                List.of(
                        second.getId(),
                        first.getId()
                ),
                habits.findByUserId(
                                owner.getId(),
                                0,
                                2
                        )
                        .stream()
                        .map(Habit::getId)
                        .toList()
        );

        /*
         * 第二页：
         * LIMIT 2 OFFSET 2
         * 前两条已经跳过，
         * 应只剩 created_at 更早的 older。
         */
        assertEquals(
                List.of(older.getId()),
                habits.findByUserId(
                                owner.getId(),
                                2,
                                2
                        )
                        .stream()
                        .map(Habit::getId)
                        .toList()
        );

        // OFFSET 已经超过记录数量，应返回空列表。
        assertTrue(
                habits.findByUserId(
                        owner.getId(),
                        3,
                        2
                ).isEmpty()
        );

        /*
         * first 明明存在，
         * 但它属于 owner，不属于 other。
         * findByUserIdAndId 必须同时检查：
         * user_id
         * +
         * habit_id
         * 所以这里应该查询不到。
         */
        assertNull(
                habits.findByUserIdAndId(
                        other.getId(),
                        first.getId()
                )
        );

        // 一个不存在的 habitId 也必须返回 null。
        assertNull(
                habits.findByUserIdAndId(
                        owner.getId(),
                        Long.MAX_VALUE
                )
        );
    }

    /**
     * 验证：
     * <ol>
     *     <li>新用户的 Habit 列表为空；</li>
     *     <li>countByUserId 返回 0；</li>
     *     <li>不存在的 user_id 不能创建 Habit。</li>
     * </ol>
     */
    @Test
    @DisplayName("习惯：空列表和零计数，不存在用户不能创建习惯")
    void habitMapperEmptyResultsAndForeignKey() {

        User empty =
                user("habit_empty");

        assertEquals(
                0,
                habits.countByUserId(empty.getId())
        );

        assertTrue(
                habits.findByUserId(
                        empty.getId(),
                        0,
                        10
                ).isEmpty()
        );

        /*
         * Long.MAX_VALUE 被当成一个不存在的 user_id。
         * database/init.sql 中：
         * habits.user_id
         * 有外键指向：
         * users.id
         * 因此 INSERT 必须失败。
         */
        Habit invalid =
                newHabit(
                        Long.MAX_VALUE,
                        "不存在用户的习惯"
                );

        DataIntegrityViolationException error =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> habits.insert(invalid)
                );

        assertTrue(
                error.getMostSpecificCause()
                        .getMessage()
                        .contains("fk_habits_user")
        );
    }


    // ========================================================================
    // CheckinRecordMapper 测试
    // ========================================================================

    /**
     * 验证 CheckinRecord 的基本插入和精确查询。
     * <p>findByUserHabitAndDate 应同时使用：
     * <pre>
     * user_id
     * habit_id
     * checkin_date
     * </pre>
     * 三个条件。
     * 任意一个条件不同，都不能查到目标记录。
     */
    @Test
    @DisplayName("打卡：完整字段往返，日期、用户和习惯条件准确隔离")
    void checkinMapperRoundTripAndLookupScope() {

        User owner =
                user("record_roundtrip");

        User other =
                user("record_other");

        /*
         * 同一个用户需要两个 Habit。
         * 由于现在同一用户 Habit 名称必须唯一，
         * 所以两个名字必须不同。
         */
        Habit target =
                habit(
                        owner.getId(),
                        "每日阅读"
                );

        Habit different =
                habit(
                        owner.getId(),
                        "每日运动"
                );

        LocalDate day =
                LocalDate.of(
                        2026,
                        9,
                        18
                );

        CheckinRecord saved =
                record(
                        owner.getId(),
                        target.getId(),
                        day
                );

        // 正常插入一条打卡记录。
        assertEquals(
                1,
                records.insert(saved)
        );

        // AUTO_INCREMENT 主键应该回填。
        assertNotNull(saved.getId());

        CheckinRecord loaded =
                records.findByUserHabitAndDate(
                        owner.getId(),
                        target.getId(),
                        day
                );

        assertNotNull(loaded);

        assertEquals(
                saved.getId(),
                loaded.getId()
        );

        assertEquals(
                owner.getId(),
                loaded.getUserId()
        );

        assertEquals(
                target.getId(),
                loaded.getHabitId()
        );

        assertEquals(
                day,
                loaded.getCheckinDate()
        );

        assertEquals(
                UTC_TIME,
                loaded.getCheckedInAt()
        );

        /*
         * 用户不同：
         * 即使 habitId 和 date 相同，也不能查到。
         */
        assertNull(
                records.findByUserHabitAndDate(
                        other.getId(),
                        target.getId(),
                        day
                )
        );

        /*
         * Habit 不同：
         * 即使 userId 和 date 相同，也不能查到。
         */
        assertNull(
                records.findByUserHabitAndDate(
                        owner.getId(),
                        different.getId(),
                        day
                )
        );

        /*
         * 日期不同：
         * 即使 userId 和 habitId 相同，也不能查到。
         */
        assertNull(
                records.findByUserHabitAndDate(
                        owner.getId(),
                        target.getId(),
                        day.plusDays(1)
                )
        );
    }

    /**
     * 验证历史打卡日期查询。
     * <p>这个 Mapper 能力以后可以给连续打卡 streak 算法使用，
     * 但这里本身只测试数据库查询，
     * 并没有真正计算 streak。
     * <p>主要验证：
     * <ol>
     *     <li>返回日期按照倒序排列；</li>
     *     <li>查询包含给定截止日期；</li>
     *     <li>未来日期不会返回；</li>
     *     <li>很久以前的历史记录不会被错误截断；</li>
     *     <li>不会串到其他用户；</li>
     *     <li>不会串到其他 Habit。</li>
     * </ol>
     */
    @Test
    @DisplayName("打卡：日期倒序、包含截止日、排除未来且不截断长历史")
    void checkinMapperDateHistory() {

        User owner =
                user("record_history");

        User other =
                user("history_other");

        Habit target =
                habit(
                        owner.getId(),
                        "历史目标习惯"
                );

        Habit different =
                habit(
                        owner.getId(),
                        "历史其他习惯"
                );

        // 使用 1 月 1 日，顺便覆盖跨年场景。
        LocalDate day =
                LocalDate.of(
                        2027,
                        1,
                        1
                );

        /*
         * 故意不按照日期顺序插入。
         * 这样可以确认最终排序来自 SQL 的 ORDER BY，
         * 而不是碰巧来自 INSERT 顺序。
         * 同时：
         * day + 1 是未来记录；
         * day - 400 是很早以前的记录。
         */
        for (LocalDate date : List.of(
                day.minusDays(400),
                day.minusDays(2),
                day.minusDays(1),
                day,
                day.plusDays(1)
        )) {

            assertEquals(
                    1,
                    records.insert(
                            record(
                                    owner.getId(),
                                    target.getId(),
                                    date
                            )
                    )
            );
        }

        /*
         * 再给另一个 Habit 插入一条历史记录。
         * target 的历史查询不应该把它查出来。
         */
        assertEquals(
                1,
                records.insert(
                        record(
                                owner.getId(),
                                different.getId(),
                                day.minusDays(3)
                        )
                )
        );

        /*
         * 查询 <= day 的记录。
         * day + 1 必须被排除；
         * 返回结果按日期 DESC 排列。
         */
        assertEquals(
                List.of(
                        day,
                        day.minusDays(1),
                        day.minusDays(2),
                        day.minusDays(400)
                ),
                records.findDatesThrough(
                        owner.getId(),
                        target.getId(),
                        day
                )
        );

        /*
         * 截止到 day - 3。
         * target 在 day - 3 本身没有记录，
         * 只剩 day - 400。
         */
        assertEquals(
                List.of(
                        day.minusDays(400)
                ),
                records.findDatesThrough(
                        owner.getId(),
                        target.getId(),
                        day.minusDays(3)
                )
        );

        /*
         * 截止日期比最早记录还早一天，
         * 因此应该没有任何结果。
         */
        assertTrue(
                records.findDatesThrough(
                        owner.getId(),
                        target.getId(),
                        day.minusDays(401)
                ).isEmpty()
        );

        /*
         * other 用户没有 target Habit 的记录。
         */
        assertTrue(
                records.findDatesThrough(
                        other.getId(),
                        target.getId(),
                        day
                ).isEmpty()
        );

        /*
         * 不存在的 habitId 也应该返回空列表。
         */
        assertTrue(
                records.findDatesThrough(
                        owner.getId(),
                        Long.MAX_VALUE,
                        day
                ).isEmpty()
        );
    }

    /**
     * 验证 checkin_records 中两个最重要的数据库约束：
     * <p>一、唯一约束：
     * <pre>
     * uk_checkin_user_habit_date
     * (user_id, habit_id, checkin_date)
     * </pre>
     * 保证同一个用户、同一个 Habit、同一天最多存在一条打卡记录。
     * <p>二、复合外键：
     * <pre>
     * fk_checkin_habit_owner
     * (user_id, habit_id)
     *      ->
     * habits(user_id, id)
     * </pre>
     * 不仅保证 Habit 存在，
     * 还保证这个 Habit 真正属于当前 user_id。
     */
    @Test
    @DisplayName("打卡：唯一约束及复合外键拒绝非法写入")
    void checkinMapperConstraints() {

        User owner =
                user("record_constraints");

        User other =
                user("constraint_other");

        Habit target =
                habit(
                        owner.getId(),
                        "约束测试习惯"
                );

        LocalDate day =
                LocalDate.of(
                        2026,
                        9,
                        18
                );

        /*
         * 第一次打卡：
         * owner + target + day
         * 应该正常成功。
         */
        assertEquals(
                1,
                records.insert(
                        record(
                                owner.getId(),
                                target.getId(),
                                day
                        )
                )
        );

        /*
         * 第二次插入完全相同：
         * owner + target + day
         * 应该触发：
         * uk_checkin_user_habit_date
         */
        DuplicateKeyException duplicate =
                assertThrows(
                        DuplicateKeyException.class,
                        () -> records.insert(
                                record(
                                        owner.getId(),
                                        target.getId(),
                                        day
                                )
                        )
                );

        assertTrue(
                duplicate.getMostSpecificCause()
                        .getMessage()
                        .contains("uk_checkin_user_habit_date")
        );

        /*
         * target Habit 属于 owner。
         * 现在故意使用 other 的 userId，
         * 形成：
         * other + target
         * 数据库中并不存在这个 (user_id, habit_id) 组合。
         * 因此复合外键必须拒绝。
         */
        CheckinRecord wrongOwner =
                record(
                        other.getId(),
                        target.getId(),
                        day
                );

        DataIntegrityViolationException wrongOwnerError =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> records.insert(wrongOwner)
                );

        assertTrue(
                wrongOwnerError.getMostSpecificCause()
                        .getMessage()
                        .contains("fk_checkin_habit_owner")
        );

        /*
         * 再测试一个完全不存在的 habitId。
         * 同样应该被复合外键拒绝。
         */
        CheckinRecord missingHabit =
                record(
                        owner.getId(),
                        Long.MAX_VALUE,
                        day
                );

        DataIntegrityViolationException missingHabitError =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> records.insert(missingHabit)
                );

        assertTrue(
                missingHabitError.getMostSpecificCause()
                        .getMessage()
                        .contains("fk_checkin_habit_owner")
        );

        /*
         * 绕过 Mapper，直接使用 JdbcTemplate 查询数据库。
         * 前面的重复 INSERT 和两个非法 INSERT 都失败，
         * 所以数据库里针对这个 Habit 最终应该仍然只有
         * 第一条合法记录。
         */
        Integer count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM checkin_records
                        WHERE user_id = ?
                          AND habit_id = ?
                          AND checkin_date = ?
                        """,
                        Integer.class,
                        owner.getId(),
                        target.getId(),
                        day
                );

        assertEquals(
                1,
                count
        );
    }


    // ========================================================================
    // 测试数据辅助方法
    // ========================================================================

    /**
     * 只构造 User 对象，不执行 INSERT。
     * <p>之所以把“构造”和“插入”分开，
     * 是因为部分测试需要故意构造非法数据，
     * 然后自己使用 assertThrows 检查 INSERT 是否失败。
     * @param username 要写入的用户名
     * @return 尚未持久化、id 仍然为空的 User
     */
    private User newUser(String username) {

        User user = new User();

        user.setUsername(username);

        /*
         * 公开的测试用 BCrypt 哈希。
         * 本阶段只是测试 password_hash 字段能否正常存取，
         * 并不是在测试真实登录和密码验证。
         */
        user.setPasswordHash(
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        );

        user.setCreatedAt(UTC_TIME);

        /*
         * updatedAt 故意和 createdAt 不完全相同，
         * 可以帮助发现数据库列映射错误。
         */
        user.setUpdatedAt(
                UTC_TIME.plusSeconds(1)
        );

        return user;
    }

    /**
     * 构造 User，然后立即通过 UserMapper 写入真实数据库。
     * <p>这是绝大部分测试创建合法用户时使用的辅助方法。
     * @param username 测试用户名
     * @return 已成功插入并回填数据库主键的 User
     */
    private User user(String username) {

        User user =
                newUser(username);

        assertEquals(
                1,
                users.insert(user)
        );

        assertNotNull(
                user.getId()
        );

        return user;
    }

    /**
     * 只构造 Habit，不执行 INSERT。
     * <p>调用方必须明确传入 name。
     * <p>不能再像旧版本一样把 Habit 名称固定写成“阅读📚”，
     * 因为当前数据库已经存在：
     * <pre>
     * UNIQUE KEY uk_habits_user_name (user_id, name)
     * </pre>
     * 同一个用户创建两个同名 Habit 会被数据库拒绝。
     * @param userId Habit 所属用户
     * @param name Habit 名称
     * @return 尚未持久化、id 为空的 Habit
     */
    private Habit newHabit(
            long userId,
            String name
    ) {

        Habit habit = new Habit();

        habit.setUserId(userId);
        habit.setName(name);
        habit.setDescription(null);
        habit.setCreatedAt(UTC_TIME);
        habit.setUpdatedAt(UTC_TIME);

        return habit;
    }

    /**
     * 构造并立即插入一个合法 Habit。
     * @param userId Habit 所属用户 ID
     * @param name Habit 名称
     * @return 插入成功并已经回填主键的 Habit
     */
    private Habit habit(
            long userId,
            String name
    ) {

        Habit habit =
                newHabit(
                        userId,
                        name
                );

        assertEquals(
                1,
                habits.insert(habit)
        );

        assertNotNull(
                habit.getId()
        );

        return habit;
    }

    /**
     * 构造 CheckinRecord，但不自动执行 INSERT。
     * <p>打卡记录故意只负责构造，因为很多测试都需要分别控制：
     * <ul>
     *     <li>第一次 INSERT 成功；</li>
     *     <li>第二次重复 INSERT 失败；</li>
     *     <li>非法用户 / Habit 组合 INSERT 失败。</li>
     * </ul>
     * @param userId 打卡用户
     * @param habitId 要打卡的 Habit
     * @param date 业务打卡日期
     * @return 尚未写入数据库的 CheckinRecord
     */
    private CheckinRecord record(
            long userId,
            long habitId,
            LocalDate date
    ) {

        CheckinRecord record =
                new CheckinRecord();

        record.setUserId(userId);
        record.setHabitId(habitId);
        record.setCheckinDate(date);

        /*
         * checkin_date 表示上海业务日期；
         * checked_in_at 表示真正发生打卡的时间。
         * 这里使用固定值，只测试数据库持久化，
         * 不测试业务时区计算。
         */
        record.setCheckedInAt(UTC_TIME);

        return record;
    }
}
