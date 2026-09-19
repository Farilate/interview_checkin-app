# 系统架构设计

## 1. 设计状态与依据

本文件同时说明当前实现和后续设计。阶段 1–3 已具备；阶段 4 已新增登录、当前用户查询、登出及 Redis Session 代码，尚未完成验收。阶段 5 的 Habit 创建、去重和分页查询已实现，真实存储接口联调仍待验收；打卡提交已实现，今日状态、连续天数、业务缓存及前端仍为后续设计；开发应遵循根目录 `AGENTS.md` 及相关文档。

后端使用 Java 21 + Spring Boot；前端使用 UniApp + Vue 3，并以 H5 作为演示目标。

习惯名称规则：不同用户允许同名，同一用户内名称唯一。业务层先 trim，数据库通过 `uk_habits_user_name(user_id, name)` 兜底；同用户重名创建返回 HTTP 409 / `40901`。

## 2. 整体架构

```mermaid
flowchart LR
    UI[UniApp Vue 3 H5] -->|HTTP REST API / JSON| API[Spring Boot 单体应用]
    API -->|持久化读写| DB[(MySQL)]
    API -->|登录态与业务缓存| Cache[(Redis)]
```

采用前后端分离的单体架构：一个前端、一个 Spring Boot 后端、一个 MySQL 数据库和一个 Redis 服务即可。后端由 Maven 管理依赖，前端使用项目对应的 npm 依赖管理方式并提交锁文件。

本期不引入微服务、消息队列、分布式事务、API 网关、事件驱动架构或复杂领域模型。

## 3. 职责与目录规划

| 层次 | 职责 | 不承担的职责 |
| --- | --- | --- |
| UniApp 页面 | 登录、创建表单、列表、打卡按钮及结果展示 | 自行计算连续天数或伪造打卡成功 |
| 前端统一请求层 | 基础 URL、Authorization、JSON 解析、公共错误处理、401 后清理登录态 | 决定用户身份或业务日期 |
| 后端身份过滤器/拦截器 | 读取 Redis 会话并构造当前用户上下文 | 信任客户端传入的 userId |
| Controller | 接收请求、参数校验、调用 Service、返回统一 DTO | SQL、日期算法及复杂业务逻辑 |
| Service | 权限归属、业务日期、事务、幂等、连续天数、缓存处理 | 页面展示逻辑 |
| Mapper（MyBatis） | 参数化 SQL、用户范围查询、插入、日期排序查询 | 身份认证和业务规则 |
| Redis 访问组件 | 会话和业务缓存读写、TTL、缓存失效 | 代替 MySQL 持久化业务记录 |
| 全局异常处理 | 统一 HTTP 状态和 `code/message/data` | 向客户端泄露 SQL、密码或堆栈 |

异常映射依据错误来源：业务层直接指定 ErrorCode；Spring MVC 使用具体异常类型选择错误码；Redis 会话边界指定 50301，数据库可用性异常指定 50302。ErrorCode 仅提供到 HTTP 状态的正向转换。无法识别原因的异常统一为 50001，不依据通用 HTTP 401/404/503 猜业务语义。

GlobalExceptionHandler 保留 ResponseEntityExceptionHandler 的具体异常注册与协议头，在统一出口按类型映射；不重复注册父类异常。容器 `/error` 仅对无异常的 404 保留路由语义，其余未知分派安全回退。未知错误日志保留类型和堆栈位置，不打印可能携带敏感信息的异常原文。未分类唯一约束冲突继续返回 50001，由后续业务 Service 识别具体约束，不能全局映射为习惯重名。

后续目录规划：

```text
interview_checkin-app/
├── backend/
├── frontend/
├── database/
│   └── init.sql
├── docs/
├── AGENTS.md
├── ARCHITECTURE.md
└── README.md
```

其中后端采用简单清晰的 `controller / service / mapper / dto / model / config / exception` 分层，不增加无必要的中间层。

## 4. 核心请求流程

### 4.1 登录

校验输入 → MySQL 查询用户 → BCrypt 验证密码 → 生成随机不透明 Token → Redis 写入带 TTL 的会话 → 返回 Token。

Redis 会话写入失败时不得返回登录成功。

当前实现由 AuthController → AuthServiceImpl → UserMapper / SessionServiceImpl 完成。用户名 trim 后按 Locale.ROOT 转小写，密码不 trim。会话值为用户 ID 字符串，固定 TTL，不滑动续期。登录响应包含 token、tokenType、expiresIn 和 user，用户 ID 为字符串。登录校验用户名范围及密码 UTF-8 字节数；演示账户通过独立 SQL 手工准备。

受保护请求由 AuthInterceptor 查询 Redis，将身份写入本次 HttpServletRequest 属性；`GET /auth/me` 再查询 MySQL 返回安全用户字段。`POST /auth/logout` 删除当前令牌对应的会话，其他令牌不受影响；重复登出被拦截并返回 401。拦截器还通过认证服务确认 MySQL 用户存在，否则撤销当前会话。Redis 会话数据访问故障统一为 50301。H5 跨域配置留待前端联调阶段实现。

### 4.2 每日打卡（提交已实现）

鉴权 → 校验习惯归属 → 读取业务日期 → 查询已有记录 → 无记录时插入 → 唯一键冲突回查 → 返回记录。当前无 Service 级事务注解，Mapper 操作按现有事务环境执行；无外层事务时独立提交。尚未接入今日状态和连续天数缓存。已改为单次读取 Instant；重复键异常按目标记录回查，不再解析消息或索引名，见 API.md 第 5 节。

每日打卡接口采用：

```http
POST /api/v1/habits/{habitId}/checkins
```

同一用户、同一习惯、同一业务日期内重复调用是幂等的：首次返回新记录，重复调用返回原记录；数据库唯一约束保证最多一条记录，真实 HTTP 并发仍待验收。

### 4.3 业务缓存查询（后续阶段）

鉴权及资源归属校验 → 查询 Redis 业务缓存 → Cache Miss 时查询 MySQL → 回填短 TTL 缓存 → 返回结果。

Redis 业务缓存只是性能优化；MySQL 始终是业务事实来源。登录会话不能从 MySQL 自动恢复，Redis 中会话不存在时必须重新登录。

## 5. 时间、身份与安全约定

身份及密码规则已用于认证模块；Clock、业务日期和资源归属已用于打卡提交。H5 存储和 CORS 属于后续阶段要求。

- 全局业务时区固定为 `Asia/Shanghai`，通过 `APP_BUSINESS_ZONE` 配置；有业务数据后不能随意修改该语义。
- 后端使用可注入 `Clock` 获取时间；一次业务请求只确定一次业务日期 D。
- `checkin_date` 存业务 `DATE`；`created_at`、`updated_at`、`checked_in_at` 等时间戳按 UTC 写入。
- 连续天数以今天或昨天为锚点，按 `LocalDate` 的自然日关系计算，不使用“24 小时毫秒差”判断相邻日期。
- Token 使用密码学安全随机值，通过 `Authorization: Bearer <token>` 传递；日志不得记录完整 Token。
- Redis Session Key 使用 Token 的 SHA-256 摘要，不直接使用明文 Token 作为 Key。
- 当前用户身份只能来自服务端会话；业务请求不接受客户端指定 `userId`。
- 他人资源与不存在资源统一返回 404，避免泄露资源归属。
- 密码使用 BCrypt 哈希存储；禁止明文、可逆加密或普通摘要替代密码哈希。
- H5 Token 暂存 `sessionStorage`；部署环境使用 HTTPS，开发 CORS 仅允许配置的前端来源。

## 6. 配置约定

| 环境变量 | 用途/默认策略 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接；真实密码不得写入源码 |
| `REDIS_HOST`、`REDIS_PORT` | 已接入；地址默认 localhost，端口默认 6379 |
| `SPRING_DATA_REDIS_DATABASE` | Spring 标准环境变量，库默认 0；未映射简写 REDIS_DATABASE |
| `SPRING_DATA_REDIS_USERNAME`、`SPRING_DATA_REDIS_PASSWORD` | Spring 标准 Redis 认证配置；未映射简写 REDIS_USERNAME / REDIS_PASSWORD |
| `APP_BUSINESS_ZONE` | 规划项：默认 `Asia/Shanghai`，Phase 6 接入 |
| `SESSION_TTL_SECONDS` | 当前 YAML 显式映射至 app.session.ttl-seconds，默认 7200 秒，须为正数；固定过期 |
| `APP_CACHE_TTL_SECONDS` | 规划项：业务缓存短 TTL，默认 30 秒 |
| `CORS_ALLOWED_ORIGINS` | 规划项：显式允许的 H5 Origin |
| `SERVER_PORT` | 默认 8080 |
| `VITE_API_BASE_URL` | 规划项：前端 API 基础地址 |
| `SPRING_PROFILES_ACTIVE` | 当前默认 local；可在运行环境覆盖 |

演示账户来自可选的 `database/demo-data.sql`，不由应用启动创建。真实本地配置位于 `backend/config/application-local.yml`，示例可提交，真实文件不提交、不打包；从 backend 工作目录读取外部配置。业务时区、业务缓存、CORS 和前端配置仍为规划项。

配置由运行环境注入，命令行和 IDEA 配置方式见 [README](README.md)。仓库只提供不含真实凭证的配置示例。

## 7. Redis 与一致性边界

MySQL 是最终业务数据来源；Redis 只承担服务端登录态和查询缓存。

业务缓存采用简单的 Cache-Aside：

1. 查询优先读 Redis，Miss 时回源 MySQL 并写入短 TTL 缓存。
2. 每日打卡以 MySQL 事务成功提交为准。
3. MySQL 提交成功后删除对应 `today` 和 `streak` 缓存。
4. 缓存删除失败时记录日志，不回滚已经成功的 MySQL 事务，由短 TTL 最终收敛。
5. 已完成鉴权的请求若仅业务缓存不可用，可以直接查询 MySQL。
6. Redis 会话不可用或无法完成鉴权时，不得绕过认证，应返回服务不可用或未登录结果。

本期不实现复杂的缓存并发控制协议、分布式锁或跨 MySQL/Redis 强一致事务。

相关文档：`docs/REQUIREMENTS.md`、`docs/DATABASE.md`、`docs/API.md`、`docs/IMPLEMENTATION_PLAN.md`、`docs/TEST_PLAN.md`。


## 8. 可选加分项：用户注册（主线完成后最后做）

注册仅作为 Phase 1–11 主线全部完成后最后考虑的可选加分项，不影响主线交付和验收。若单独授权实施：请求 DTO → Service 校验与 BCrypt → Mapper 写入 users，由用户名唯一约束保证并发安全。不使用启动回调创建用户。是否公开放行、是否注册后创建 Redis Session 在实施前确定；自动登录若被采用，必须明确 MySQL 已提交而 Session 写入失败时的行为。具体规划见 IMPLEMENTATION_PLAN.md 第 6 节。
