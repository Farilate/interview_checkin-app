# REST API 设计

实现范围：认证接口已实现，第 2 节描述其当前行为；第 3–8 节为后续习惯、打卡及前端契约。真实 MySQL/Redis 认证联调仍待验收，测试状态见 [测试计划](TEST_PLAN.md)。

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

ID 使用十进制正整数字符串，登录响应与 `/auth/me` 均遵循该约定。时间戳为 ISO 8601 UTC，例如 `2026-09-17T02:00:00.000Z`；业务日期为 `YYYY-MM-DD`。

登录响应及受保护接口响应设置 `Cache-Control: no-store`；后端 Redis 业务缓存不改变 HTTP 缓存语义。

### 已实现的公共处理

- `ApiResponse<T>` 是统一响应 DTO；成功响应显式调用 `ok`，创建资源由 Controller 设置 HTTP 201。错误响应始终包含 `data:null`。
- `ErrorCode` 集中管理 HTTP 状态、业务码和安全提示，`BusinessException` 只携带预定义错误码。
- 映射方向为“明确业务原因或异常类型 → ErrorCode → HTTP 状态”，不提供从 HTTP 状态反推业务错误的方法。业务异常直接使用自身错误码；Redis 会话故障在会话边界转换为 50301，数据库可用性异常保持 50302。
- `GlobalExceptionHandler` 处理 MVC 参数绑定、`@Valid` 请求体校验、方法参数校验、JSON 解析、404、405、406、415、业务和数据库异常；405 保留 `Allow` 响应头。
- JSON 请求严格拒绝 DTO 未声明字段和尾随内容；后续业务 DTO 不声明 `userId` 等服务端字段。查询参数与无 Body 接口的专属限制在对应 Controller 实现时落实。
- `PageRequest` 默认 page=1/pageSize=20，校验 page≥1、1≤pageSize≤100；偏移量使用 long，避免整数乘法溢出。
- 连接及临时数据访问/事务故障返回 50302；未分类数据库异常（包括未识别的唯一键冲突）返回 50001，不擅自映射成业务成功。具体重名或重复打卡约束的识别在业务阶段处理。
- `ApiErrorController` 处理容器错误分派，替代默认 HTML 错误页；响应不包含异常原文、SQL、凭证和堆栈。API 响应统一设置 `Cache-Control: no-store`。
- MVC 中明确的路由异常返回 40400；通用 `ResponseStatusException` / `ErrorResponseException` 无论携带哪个 HTTP 状态，都不能代表具体业务原因，回退 HTTP 500 / 50001。返回值校验失败、缺少服务端路径变量声明对应值及转换器配置错误也归为内部错误。
- `/error` 仅将无附带异常的容器 404 识别为路由不存在；直接访问 `/error` 同样返回 40400。其余状态或附带未知异常的分派回退 50001，不将原始 401/503 猜成认证或基础设施业务错误。
- 未知异常在服务端记录异常类型和堆栈位置；不直接打印异常消息、cause 原文或请求参数，避免日志泄露凭证。客户端只收到预定义安全提示。
- 验证用 `/probe/*` 接口只存在于测试目录，不打包进生产应用；其测试结果不替代业务接口验收。

## 2. 登录

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

需要登录，无需请求体。拦截器读取 Redis 会话，将用户 ID 写入当前请求属性，服务层再按该 ID 查询 MySQL；不从客户端参数取得身份。

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

需要登录，携带 `Authorization: Bearer <token>`，无需请求体。删除当前令牌摘要对应的 Redis 会话，成功返回 HTTP 200：

```json
{ "code": 0, "message": "ok", "data": null }
```

只撤销当前令牌，不删除 MySQL 用户或其他登录会话。已登出、已过期或不存在的令牌会被拦截器拒绝，重复登出返回 HTTP 401 / `40101`，不会再次返回 200。若会话在认证后、删除前过期，删除操作仍可正常完成；Redis 删除异常则不返回成功。

### 当前认证实现边界

`/api/v1/**` 注册认证拦截器，仅排除 `/api/v1/auth/login`。请求属性只在当前请求内有效，不使用 ThreadLocal。会话读取不续期，TTL 来自 `app.session.ttl-seconds`，YAML 映射环境变量为 `SESSION_TTL_SECONDS`，默认 7200 秒。当前未配置跨域规则或单独处理 OPTIONS，H5 跨域预检尚待联调。

用户名 trim 后须为 3–32 位 ASCII 字母、数字或下划线，再统一转小写。密码须非空白且为 8–72 个 UTF-8 字节，不 trim。登录与演示账户初始化共用这些规则。Session TTL 非正数或不能安全转换为毫秒时在启动阶段拒绝。

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

不允许传 `userId`。

Response：HTTP 201。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "101",
    "name": "每日阅读",
    "description": "阅读二十分钟",
    "createdAt": "2026-09-17T01:00:00.000Z"
  }
}
```

主要错误：`40001` 名称空白、字段超长或非法字段；HTTP 409 / `40901` 当前用户已存在同名打卡项。

不同用户允许同名习惯；同一用户内名称唯一。名称先 trim，数据库按 `uk_habits_user_name(user_id, name)` 及列排序规则判重；并发创建时也必须将该约束冲突映射为 HTTP 409 / `40901`。

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
        "createdAt": "2026-09-17T01:00:00.000Z"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

无记录或超过末页时 `items=[]`，`total` 仍表示该用户总数。

## 5. 每日打卡

### `PUT /api/v1/habits/{habitId}/checkins/today`

登录要求：是。

Request：无 Body；不接受 `userId`、日期、时间戳或连续天数。

该接口按“当前用户 + 当前习惯 + 当前业务日期”定义资源，因此重复调用具有幂等语义。

首次打卡 Response：HTTP 200。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "recordId": "501",
    "habitId": "101",
    "date": "2026-09-17",
    "businessZone": "Asia/Shanghai",
    "checkedIn": true,
    "created": true,
    "checkedInAt": "2026-09-17T02:00:00.000Z",
    "streakDays": 3,
    "streakEndDate": "2026-09-17"
  }
}
```

同一业务日期重复打卡：

```json
{
  "code": 0,
  "message": "今日已打卡",
  "data": {
    "recordId": "501",
    "habitId": "101",
    "date": "2026-09-17",
    "businessZone": "Asia/Shanghai",
    "checkedIn": true,
    "created": false,
    "checkedInAt": "2026-09-17T02:00:00.000Z",
    "streakDays": 3,
    "streakEndDate": "2026-09-17"
  }
}
```

主要错误：

- `40001`：非法 habitId 或传入服务端专属字段。
- `40401`：习惯不存在或不属于当前用户。

只有 `uk_checkin_user_habit_date` 唯一约束冲突可以按重复成功处理；其他数据库错误不得吞掉。

幂等范围是同用户、同习惯、同业务日期。跨午夜后的新请求属于新业务日期，可以产生新一天的一条合法记录。

## 6. 查询今日打卡状态

### `GET /api/v1/habits/{habitId}/checkins/today`

登录要求：是。

无 Body、无日期参数。

Response：HTTP 200。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "habitId": "101",
    "date": "2026-09-17",
    "businessZone": "Asia/Shanghai",
    "checkedIn": false,
    "recordId": null,
    "checkedInAt": null
  }
}
```

没有今日记录是正常 `checkedIn=false`，不是 404。

## 7. 查询当前连续打卡天数

### `GET /api/v1/habits/{habitId}/streak`

登录要求：是。

无 Body、无日期参数。

今天尚未打卡但昨天有记录时，返回截至昨天的连续长度。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "habitId": "101",
    "asOfDate": "2026-09-17",
    "businessZone": "Asia/Shanghai",
    "streakDays": 2,
    "streakEndDate": "2026-09-16"
  }
}
```

无当前连续记录时：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "habitId": "101",
    "asOfDate": "2026-09-17",
    "businessZone": "Asia/Shanghai",
    "streakDays": 0,
    "streakEndDate": null
  }
}
```

## 8. 前端消费约定

前端统一请求层集中处理：

- API 基础地址。
- `Authorization: Bearer`。
- 统一 `code/message/data`。
- 网络错误与超时。
- 401 清理本地 Token 并引导重新登录。
- 503 保留登录信息并提示稍后重试，不错误地当成“退出登录”。

打卡按钮提交中可禁用以改善体验，但并发正确性不能依赖前端按钮状态。

`PUT /checkins/today` 成功后，页面直接使用写接口响应中的 `checkedIn` 和 `streakDays` 更新，不需要立即再请求 GET 覆盖该结果。

独立 GET 使用 Redis 短 TTL 缓存；若缓存刚好仍是旧值，后续会在短 TTL 过期或写操作失效缓存后回到 MySQL 真实结果。页面重新激活或跨日时应重新查询，以后端业务日期为准。
