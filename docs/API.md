# REST API 设计

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

目标契约要求 ID 使用十进制正整数字符串；当前 `/auth/me` 的 `id` 实际输出为数字，尚待修正。时间戳为 ISO 8601 UTC，例如 `2026-09-17T02:00:00.000Z`；业务日期为 `YYYY-MM-DD`。

登录响应及受保护接口响应设置 `Cache-Control: no-store`；后端 Redis 业务缓存不改变 HTTP 缓存语义。

### 阶段 3 已实现的公共处理

- `ApiResponse<T>` 是统一响应 DTO；成功响应显式调用 `ok`，创建资源由 Controller 设置 HTTP 201。错误响应始终包含 `data:null`。
- `ErrorCode` 集中管理 HTTP 状态、业务码和安全提示，`BusinessException` 只携带预定义错误码。
- `GlobalExceptionHandler` 处理 MVC 参数绑定、`@Valid` 请求体校验、方法参数校验、JSON 解析、404、405、406、415、业务和数据库异常；405 保留 `Allow` 响应头。
- JSON 请求严格拒绝 DTO 未声明字段和尾随内容；后续业务 DTO 不声明 `userId` 等服务端字段。查询参数与无 Body 接口的专属限制在对应 Controller 实现时落实。
- `PageRequest` 默认 page=1/pageSize=20，校验 page≥1、1≤pageSize≤100；偏移量使用 long，避免整数乘法溢出。
- 连接及临时数据访问/事务故障返回 50302；未分类数据库异常（包括未识别的唯一键冲突）返回 50001，不擅自映射成业务成功。具体重名或重复打卡约束的识别在业务阶段处理。
- `ApiErrorController` 处理容器错误分派，替代默认 HTML 错误页；响应不包含异常原文、SQL、凭证和堆栈。API 响应统一设置 `Cache-Control: no-store`。
- 阶段 4 已有登录、当前用户查询和登出实现，下面第 2 节说明实际行为及待补齐项；习惯和打卡章节仍为待实现契约。验证用 `/probe/*` 接口只存在于测试目录，不打包进生产应用。

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
    "token": "<随机不透明令牌>"
  }
}
```

主要错误：

- `40001`：当前覆盖用户名或密码空白、JSON 解析失败及未知字段等；长度与字符范围规则尚未补齐。
- `40102`：用户名或密码错误。
- `50301`：Redis Session 写入失败的目标错误码；当前 Session 服务直接传播 Redis 异常，全局处理器可能映射为 `50302` 或 `50001`，尚未实现专门分类。

响应不得包含 `password_hash`。本期允许同一用户存在多个独立登录 Session，各自固定过期。

原目标响应中的 `tokenType`、`expiresIn`、`user` 当前均未返回；联调应以以上实际示例为准，不能将这些字段当作已实现。Token 是 32 个随机字节经无填充 URL 安全 Base64 编码得到的 43 字符字符串，不是 JWT。

### `GET /api/v1/auth/me`

需要登录，无需请求体。拦截器读取 Redis 会话，将用户 ID 写入当前请求属性，服务层再按该 ID 查询 MySQL；不从客户端参数取得身份。

HTTP 200 当前响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "id": 1, "username": "demo_user" }
}
```

缺少请求头、前缀不是当前实现要求的 `Bearer `、空令牌、会话失效均返回 HTTP 401 / `40101`。MySQL 用户不存在也返回 `40101`，但当前不会删除遗留会话；拦截器本身不检查 MySQL 用户存在性。数字 ID 尚不符合统一字符串 ID 约定。

### `POST /api/v1/auth/logout`

需要登录，携带 `Authorization: Bearer <token>`，无需请求体。删除当前令牌摘要对应的 Redis 会话，成功返回 HTTP 200：

```json
{ "code": 0, "message": "ok", "data": null }
```

只撤销当前令牌，不删除 MySQL 用户或其他登录会话。已登出、已过期或不存在的令牌会被拦截器拒绝，重复登出返回 HTTP 401 / `40101`，不会再次返回 200。若会话在认证后、删除前过期，删除操作仍可正常完成；Redis 删除异常则不返回成功。

### 当前认证实现边界

`/api/v1/**` 注册认证拦截器，仅排除 `/api/v1/auth/login`。请求属性只在当前请求内有效，不使用 ThreadLocal。会话读取不续期，TTL 来自 `app.session.ttl-seconds`，YAML 映射环境变量为 `SESSION_TTL_SECONDS`，默认 7200 秒。当前未配置跨域规则或单独处理 OPTIONS，H5 跨域预检尚待联调。

用户名目前仅校验非空白并执行 trim、小写转换；密码仅校验非空白且不 trim。需求规定的用户名 3–32 位 ASCII 范围、密码 8–72 个 UTF-8 字节仍是验收要求，不能视为已经实现。

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
