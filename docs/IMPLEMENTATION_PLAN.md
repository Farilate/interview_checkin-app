# 分阶段开发计划

## 1. 执行边界

以下是后续实施计划，不是已完成清单。每次只执行用户明确授权的阶段；阶段结束后汇报并停止，不因为本计划存在就自动连续实现后续阶段，也不自动提交 Git commit。

| 阶段 | 工作范围及预期交付 | 验证与完成条件 |
| --- | --- | --- |
| Phase 1：Spring Boot 项目初始化 | 核对并固定 JDK/Spring Boot 兼容版本；创建 `backend/`、`pom.xml`、Maven Wrapper、启动入口、基础配置与 `.gitignore` 补充 | Maven 构建通过；环境变量来源明确；不提前写登录/打卡业务 |
| Phase 2：MySQL 表和基础持久层 | 创建 `database/init.sql`；落实 users、habits、checkin_records、复合外键及唯一索引；引入 MyBatis 和 Mapper | 在真实 MySQL 空库执行脚本成功；重复打卡三元组被数据库拒绝；用户名唯一约束有效；同用户习惯名称唯一、跨用户同名允许 |
| Phase 3：统一响应和异常处理 | 统一 DTO、参数校验、全局异常处理、HTTP/code 映射 | 合法与非法请求均符合 `API.md`；内部错误不泄露 SQL、凭证或堆栈 |
| Phase 4：登录和 Redis 登录态 | BCrypt 验证；随机 Token；Redis Session；当前用户上下文；初始化演示账户 | 正确/错误密码、账户不存在、Session 过期均符合契约；Redis 中 Session 有真实 TTL；前端 userId 不能替代登录身份 |
| Phase 5：打卡项管理 | 创建习惯、当前用户分页列表、名称校验及用户隔离 | 数据真实写入 MySQL；刷新后仍可查询；不同用户互相隔离；空列表正常 |
| Phase 6：每日打卡和并发安全 | 统一 Clock/业务日期；实现 `PUT /habits/{habitId}/checkins/today`；事务插入和指定唯一约束冲突处理；今日状态查询 | 首次 `created=true`；重复/并发 `created=false`；数据库最终只有一条；其他数据库异常不得伪装成功 |
| Phase 7：连续打卡算法 | 后端按 `LocalDate` 计算 streak；完善 streak GET 接口和打卡响应 | 验证今天/昨天锚点、断签、跨月、跨年；结果不使用总次数代替 |
| Phase 8：Redis 业务缓存 | 接入 today/streak 短 TTL 缓存；Cache Miss 回源；打卡提交后删除相关缓存；业务缓存失败回退 MySQL | 第二次查询可命中缓存；手动删除 Key 后能回源；打卡后旧缓存被失效；缓存故障不影响已提交 MySQL 数据 |
| Phase 9：UniApp 前端 | 创建 Vue 3 UniApp H5 工程；统一请求层、登录页、列表页、创建表单和打卡交互 | H5 构建成功；页面真实请求后端；处理 401、503、提交中、空列表和错误提示 |
| Phase 10：前后端联调 | 跑通登录 → 创建 → 查询 → 打卡 → 今日状态/连续天数 → 刷新 | 浏览器 Network、MySQL 三表和 Redis 三类 Key 相互印证；刷新后业务结果仍存在；多用户隔离有效 |
| Phase 11：测试、README 和演示准备 | 执行 `TEST_PLAN.md` 必做测试；完善启动、环境变量、数据库初始化、Redis Key 和算法说明；准备演示 | 干净环境可按 README 启动；提供依赖清单和 SQL；必做测试结果明确；可选准备 3 分钟录屏 |

## 2. 已确认的实现决策

以下决策不再作为待确认项：

- 业务时区：`Asia/Shanghai`。
- 今天未打卡但昨天已打卡时，连续天数保留截至昨天。
- 每日打卡接口：`PUT /api/v1/habits/{habitId}/checkins/today`。
- 重复打卡按幂等成功返回，`created=false`。
- 本期不做注册；使用显式初始化演示账户。
- 不同用户允许同名习惯；同一用户内习惯名称唯一。
- Session 默认 TTL：7200 秒。
- 业务缓存采用短 TTL Cache-Aside；MySQL 为最终事实来源。

## 3. 阶段依赖与增量契约

Phase 2 提供真实持久层；Phase 4 开始需要真实 MySQL 和 Redis；Phase 5 以后依赖后端当前用户身份。

Phase 6 先解决每日打卡写入安全、幂等和今日状态；Phase 7 再完成连续天数的完整算法。Phase 6 不用假数据伪造 `streakDays` 已经完成。

Phase 8 只增加业务查询缓存；Redis Session 已在 Phase 4 中真实使用。Phase 9 必须在后端接口可用后再接入，不用 Mock 假装核心业务已完成。

## 4. 每阶段检查规则

每个阶段都遵循：

1. 开始前读取 `AGENTS.md` 和相关设计文档。
2. 只修改当前阶段需要的文件。
3. 数据结构变化同步 `database/init.sql`。
4. API 变化同步 `docs/API.md` 和前端调用。
5. Redis 设计变化同步 `docs/DATABASE.md`。
6. 执行与本次改动直接相关的测试或构建。
7. 没有执行的验证必须明确标记“未验证”，不能声称通过。
8. 不自动提交 Git；是否 commit 由用户决定。

测试允许在隔离测试库构造历史日期数据，但不能为了演示新增“任意指定打卡日期”的生产 API。

## 5. Phase 1 需要核对但不影响业务设计的环境项

进入 Phase 1 时再根据本机环境确定并记录：

- JDK 版本：已确定为 Java 21。
- Spring Boot 版本。
- Maven 版本或 Maven Wrapper。
- MySQL 版本。
- Redis 版本。
- Node / npm 版本。
- UniApp/HBuilderX 或 CLI 的本地启动方式。

这些属于开发环境选择，不改变已经确认的业务规则。
