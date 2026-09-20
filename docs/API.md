# REST API 设计

实现范围：认证、Habit 创建/去重/分页、打卡提交、今日状态和连续天数已实现，第 2–7 节描述当前行为；第 8 节为后续前端消费约定。真实 MySQL/Redis 接口联调仍待验收，测试状态见 [测试计划](TEST_PLAN.md)。

## 1. 公共约定

基础路径：

```text
/api/v1
```

JSON 使用 UTF-8。受保护接口携带：

```http
Authorization: Bearer <token>
```

服务端从 Redis Session 获取 `userId`，业务接口不接受客户端身份字段。

所有响应统一使用：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

失败时 `data=null`。HTTP 状态表达协议结果，`code` 表达业务分类，前端不得通过解析 `message` 决定业务逻辑。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | 40001 | 参数缺失、格式错误、未知字段或服务端专属字段 |
| 401 | 40101 | Token 缺失、格式错误、失效或 Session 用户已不存在 |
| 401 | 40102 | 用户名或密码错误；用户不存在也返回此项 |
| 404 | 40401 | 习惯不存在或不属于当前用户 |
| 404 | 40400 | API 路由不存在 |
| 405 | 40500 | HTTP Method 不支持 |
| 406 | 40600 | Accept 指定的响应格式不支持 |
| 409 | 40901 | 当前用户已存在同名打卡项 |
| 415 | 41500 | Content-Type 指定的请求数据格式不支持 |
| 503 | 50301 | Redis Session 服务不可用 |
| 503 | 50302 | 数据库连接不可用或临时事务故障 |
| 500 | 50001 | 非预期服务器错误，隐藏内部细节 |

ID 使用十进制正整数字符串，认证与 Habit 响应均遵循该约定，避免 H5 大整数精度丢失。时间戳为带 Z 的 ISO 8601 UTC；业务日期为 `YYYY-MM-DD`。Habit 持久层保留 UTC LocalDateTime，在响应映射时显式转为 Instant，不依赖服务器默认时区。

登录响应及受保护接口响应设置 `Cache-Control: no-store`；后端 Redis 业务缓存不改变 HTTP 缓存语义。

### 已实现的公共处理

- `ApiResponse<T>` 是统一响应 DTO；成功响应显式调用 `ok`，Habit 创建返回 HTTP 201，列表查询返回 HTTP 200。错误响应始终包含 `data:null`。
- `ErrorCode` 集中管理 HTTP 状态、业务码和安全提示，`BusinessException` 只携带预定义错误码。
- 映射方向为“明确业务原因或异常类型 → ErrorCode → HTTP 状态”，不提供从 HTTP 状态反推业务错误的方法。业务异常直接使用自身错误码；Redis 会话故障在会话边界转换为 50301，数据库可用性异常保持 50302。
- `GlobalExceptionHandler` 处理 MVC 参数绑定、`@Valid` 请求体校验、方法参数校验、JSON 解析、404、405、406、415、业务和数据库异常；405 保留 `Allow` 响应头。
- JSON DTO 接口严格拒绝未知字段和尾随内容；无 Body 接口不读取请求体，客户端不应发送额外请求体。查询参数仅绑定接口明确声明的参数；额外 Body 或查询参数中的 userId 不作为身份来源，不增加全局请求体检测 Filter。
- `PageRequest` 默认 page=1/pageSize=20，校验 page≥1、1≤pageSize≤100；偏移量使用 long，避免整数乘法溢出。
- 连接及临时数据访问/事务故障返回 50302；未分类数据库异常（包括未识别的唯一键冲突）返回 50001，不擅自映射成业务成功。具体重名或重复打卡约束的识别在业务阶段处理。
- `ApiErrorController` 处理容器错误分派，替代默认 HTML 错误页；响应不包含异常原文、SQL、凭证和堆栈。API 响应统一设置 `Cache-Control: no-store`。
- MVC 中明确的路由异常返回 40400；通用 `ResponseStatusException` / `ErrorResponseException` 无论携带哪个 HTTP 状态，都不能代表具体业务原因，回退 HTTP 500 / 50001。返回值校验失败、缺少服务端路径变量声明对应值及转换器配置错误也归为内部错误。
- `/error` 仅将无附带异常的容器 404 识别为路由不存在；直接访问 `/error` 同样返回 40400。其余状态或附带未知异常的分派回退 50001，不将原始 401/503 猜成认证或基础设施业务错误。
- 未知异常在服务端记录异常类型和堆栈位置；不直接打印异常消息、cause 原文或请求参数，避免日志泄露凭证。客户端只收到预定义安全提示。
- 验证用 `/probe/*` 接口只存在于测试目录，不打包进生产应用；其测试结果不替代业务接口验收。

## 2. 登录

注册接口仅作为主线全部完成后最后考虑的可选加分项，不属于当前主线 API 交付范围，拟使用 `POST /api/v1/auth/register`，当前未实现且未加入匿名放行列表。注册请求拟只包含 username/password，沿用现有凭证规则；成功状态、响应字段、自动登录策略和用户名冲突业务码在实施前确定。用户名冲突规划为 HTTP 409，不复用习惯名称冲突的 40901。完整范围见 IMPLEMENTATION_PLAN.md 第 6 节，以下登录接口仍为当前已实现契约。

### `POST /api/v1/auth/login`

登录要求：否。

Request：

```json
{
  "username": "demo_user",
  "password": "<运行时输入的密码>"
}
```

Response：HTTP 200。只有 Redis Session 写入成功后才返回登录成功。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "<随机不透明令牌>",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": { "id": "1", "username": "demo_user" }
  }
}
```

主要错误：

- `40001`：用户名或密码空白、用户名字符范围或长度错误、密码 UTF-8 字节数越界、JSON 解析失败及未知字段等。
- `40102`：用户名或密码错误。
- `50301`：Redis Session 读、写、删除的数据访问故障，由会话服务边界统一分类，不当作 MySQL 故障。

响应不得包含 `password_hash`。本期允许同一用户存在多个独立登录 Session，各自固定过期。

`expiresIn` 与创建会话使用的 TTL 来自同一个配置值，单位为秒；`user` 只包含 ID 和用户名。Token 是 32 个随机字节经无填充 URL 安全 Base64 编码得到的 43 字符字符串，不是 JWT。

### `GET /api/v1/auth/me`

需要登录。客户端不应发送 Body，服务端不读取 Body；多余内容不用于确定身份。拦截器读取 Redis 会话，将用户 ID 写入当前请求属性，服务层再按该 ID 查询 MySQL；不从客户端参数取得身份。

HTTP 200 当前响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "id": "1", "username": "demo_user" }
}
```

缺少请求头、前缀不是当前实现要求的 `Bearer `、空令牌、会话失效均返回 HTTP 401 / `40101`。拦截器通过认证服务确认 MySQL 用户仍存在，不存在则撤销当前会话并返回 `40101`；若 Redis 清理失败则返回 `50301`，不会放行。损坏或非正数会话身份也会清理后按未登录处理。

### `POST /api/v1/auth/logout`

需要登录，携带 `Authorization: Bearer <token>`。客户端不应发送 Body，服务端不读取 Body。删除当前令牌摘要对应的 Redis 会话，成功返回 HTTP 200：

```json
{ "code": 0, "message": "ok", "data": null }
```

只撤销当前令牌，不删除 MySQL 用户或其他登录会话。已登出、已过期或不存在的令牌会被拦截器拒绝，重复登出返回 HTTP 401 / `40101`，不会再次返回 200。若会话在认证后、删除前过期，删除操作仍可正常完成；Redis 删除异常则不返回成功。

### 当前认证实现边界

在当前生产 MVC 默认资源映射下，未携带 Authorization 访问不存在的 `/api/v1/**` 路径（不包括匿名登录地址）时，认证拦截先执行，返回 HTTP 401 / 40101，而非 404。同一路径携带有效 Token 并通过用户存在性检查后返回 HTTP 404 / 40400；未受保护的未知路径也返回 40400。这三种情况均由加载生产 WebMvcConfig 和 AuthInterceptor 的真实 HTTP 测试锁定。不要将“所有未知地址都返回 404”作为契约。

`/api/v1/**` 注册认证拦截器，仅排除 `/api/v1/auth/login`。请求属性只在当前请求内有效，不使用 ThreadLocal。会话读取不续期，TTL 来自 `app.session.ttl-seconds`，YAML 映射环境变量为 `SESSION_TTL_SECONDS`，默认 7200 秒。当前未配置跨域规则或单独处理 OPTIONS，H5 跨域预检尚待联调。

用户名 trim 后须为 3–32 位 ASCII 字母、数字或下划线，再统一转小写。密码须非空白且为 8–72 个 UTF-8 字节，不 trim。演示 SQL 中的账户也遵循这些规则。Session TTL 非正数或不能安全转换为毫秒时在启动阶段拒绝。

## 3. 创建打卡项

### `POST /api/v1/habits`

登录要求：是。

Request：

```json
{
  "name": "每日阅读",
  "description": "阅读二十分钟"
}
```

不允许在 JSON 中传 userId、id 等未知字段。名称非空白，先 trim，再按 Unicode 码点校验 1–50；描述可省略或为 null，按 Unicode 码点校验最多 200，空字符串或纯空白归一为 null，有内容的描述保留原文。码点不是 UTF-16 单元，也不是组合字符的显示宽度；例如单个 📚 计为一个码点。额外查询参数 userId 不参与身份判断。

Response：HTTP 201。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "101",
    "name": "每日阅读",
    "description": "阅读二十分钟",
    "createdAt": "2026-09-17T01:00:00Z",
    "updatedAt": "2026-09-17T01:00:00Z"
  }
}
```

主要错误：`40001` 名称空白、字段超长或非法字段；HTTP 409 / `40901` 当前用户已存在同名打卡项。

不同用户允许同名习惯；同一用户内名称唯一。名称先 trim，数据库按 `uk_habits_user_name(user_id, name)` 及列排序规则判重。业务先查询，再由数据库约束兜底；插入异常仅在 JDBC 原因同时满足 MySQL 1062、SQLState 23000 且完整键名为 uk_habits_user_name（允许库/表前缀）时转换为 HTTP 409 / `40901`。主键、其他索引、无法识别的消息格式仍交给统一异常处理返回 50001，客户端不接收数据库原文。真实并发唯一性仍需 MySQL 接口联调验证。

## 4. 查询当前用户打卡项列表

### `GET /api/v1/habits?page=1&pageSize=20`

登录要求：是。

规则：

- 无 Body。
- `page` 默认 1，最小 1。
- `pageSize` 默认 20，范围 1–100。
- 只查询当前 Session 用户。
- 按 `created_at DESC, id DESC` 稳定排序。

Response：HTTP 200。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": "101",
        "name": "每日阅读",
        "description": "阅读二十分钟",
        "createdAt": "2026-09-17T01:00:00Z",
        "updatedAt": "2026-09-17T01:00:00Z"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

无记录或超过末页时 `items=[]`，`total` 仍表示该用户总数。

非法分页（非数字、超出 int 范围、page<1、pageSize 不在 1–100）返回 HTTP 400 / 40001。偏移使用 long；列表与总数分别查询，当前不承诺并发写入下的同一快照。排序由 Mapper SQL 保证。

## 5. 每日打卡（提交已实现）

### `PUT /api/v1/habits/{habitId}/checkins/today`

需要登录。已改为 PUT 今日资源路径，旧 POST /habits/{habitId}/checkins 已移除（有效认证下返回 40400）；对新路径发送 POST 返回 40500。请求不需要 Body；服务端不读取额外 Body，也不使用查询参数里的 userId、日期或时间。

首次和同日重复打卡均返回 HTTP 200、code=0。首次创建返回 created=true；顺序重复或并发冲突后回查成功返回 created=false，并保留原记录 ID 和原始时间。不返回 checkedIn、businessZone、recordId、date 或连续天数字段。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "501",
    "habitId": "101",
    "checkinDate": "2026-09-20",
    "checkedInAt": "2026-09-19T16:00:00.123Z",
    "created": true
  }
}
```

业务日期由可注入 Clock 的时区确定，TimeConfig 默认 Asia/Shanghai，可通过 app.business-zone（环境变量 APP_BUSINESS_ZONE）配置；有业务数据后不能随意改变时区。时间按 UTC 毫秒保存，与 DATETIME(3) 对齐。ID 按字符串返回。

- 缺少或失效令牌：40101。
- habitId 为 0、负数、非数字或超出 long 范围：40001，在访问业务 Mapper 前拒绝。
- 习惯不存在或属于他人：40401，不访问打卡记录。
- 数据库连接故障：50302；其他未分类数据异常：50001。
- 已存在同日记录：直接返回，不再次插入；插入发生 DuplicateKeyException 后按当前用户、习惯、日期回查，找到则返回原记录，查不到则继续抛出原异常，不能伪装成功。

**已修复与验收边界：**每次请求只读取一次 Clock.instant()，先截断到毫秒，再从同一 Instant 派生业务日期和 UTC 时间。重复键处理只根据目标三元组记录是否存在确认幂等结果，不解析消息、索引名称或 JDBC 错误号。此策略确认目标记录已存在，不推断原异常具体来自哪个索引。真实 MySQL 的 Service 层 10 线程并发已通过，HTTP + Redis 鉴权端到端并发尚未验收。

## 6. 查询今日打卡状态（已实现）

### `GET /api/v1/habits/{habitId}/checkins/today`

需要登录，不需要 Body。按 Clock 的业务时区查询今天，只读取当前用户拥有的习惯；客户端传入的日期和 userId 不参与查询条件。

HTTP 200，当前响应只包含 checkedIn：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "checkedIn": false }
}
```

今天有记录返回 true；无记录是正常 false，不是 404。与早期设计不同，当前不返回 habitId、date、businessZone、recordId、checkedInAt。

## 7. 查询当前连续打卡天数（已实现）

### `GET /api/v1/habits/{habitId}/streak`

需要登录，不需要 Body。按当前业务日期从 MySQL 读取截至当天的倒序打卡日期。今天有记录则从今天起算；今天没有但昨天有记录则从昨天起算；两天都没有记录则为 0。遇到断签停止，不使用总条数或历史最大连续天数代替当前连续天数。

HTTP 200，当前响应只包含 streak：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "streak": 2 }
}
```

无记录返回 streak=0。字段名是 streak，当前不返回原设计的 streakDays、streakEndDate、asOfDate、habitId 或 businessZone。

两个 GET 均设置 Cache-Control: no-store；缺少/失效令牌返回 40101，习惯不存在或属于他人返回 40401。0、负数、非数字或 long 溢出均返回 40001；合法正整数但资源不存在/无权访问仍返回 40401。数据库故障按统一分类返回错误，不能伪装为 false 或 0。当前查询直接访问 MySQL，尚未接入 Redis today/streak 缓存。

## 8. 前端消费约定

前端统一请求层集中处理：

- API 基础地址。
- `Authorization: Bearer`。
- 统一 `code/message/data`。
- 网络错误与超时。
- 401 清理本地 Token 并引导重新登录。
- 503 保留登录信息并提示稍后重试，不错误地当成“退出登录”。

打卡按钮提交中可禁用以改善体验，但并发正确性不能依赖前端按钮状态。

PUT /habits/{habitId}/checkins/today 成功后使用记录和 created 标记更新页面；created=false 可展示“今日已打卡”。刷新或页面重新激活时调用今日状态 GET 读取 checkedIn，调用连续天数 GET 读取 streak，不再读取旧字段名。

两个 GET 当前直接读取 MySQL；后续 Phase 8 再接入 Redis 短 TTL 业务缓存。跨日或页面重新激活时应重新查询，连续天数由后端计算。
