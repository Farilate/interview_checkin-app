# MySQL 与 Redis 设计

## 1. MySQL 通用约定

使用 InnoDB、utf8mb4。三张核心表采用 BIGINT UNSIGNED 自增主键；目标要求 API 中 ID 以字符串传输，当前 `/auth/me` 数字 ID 是待修正差异。

除显式标注 `NULL` 外，字段均为 `NOT NULL`。时间戳字段使用 `DATETIME(3)`，由应用按 UTC 写入；`checkin_date` 为 `Asia/Shanghai` 对应的业务 `DATE`。

Phase 2 已提供可执行建表脚本（在显式选中的空库执行一次，不自动建库、删表或插入账户）：

```text
database/init.sql
```

持久层使用 MyBatis Spring Boot Starter 4.1.0，Mapper 位于 `backend/src/main/java/com/example/checkin/mapper/`。连接由 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 注入；应用不自动初始化表。连接会话设为 UTC，时间字段仍由业务调用方显式提供 UTC 值。真实 MySQL 验证方式见 README 的 Phase 2 集成测试说明。

## 2. users

| 字段 | 类型 | 默认/含义 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | 自增主键 |
| username | VARCHAR(32) | 规范化为小写 ASCII 用户名，精确比较 |
| password_hash | VARCHAR(255) | BCrypt 完整编码哈希 |
| created_at | DATETIME(3) | 创建 UTC 时间 |
| updated_at | DATETIME(3) | 最后更新 UTC 时间 |

约束与索引：

- `PRIMARY KEY(id)`
- `UNIQUE uk_users_username(username)`

数据库不得存储明文密码或 Session Token。

## 3. habits

| 字段 | 类型 | 默认/含义 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | 自增主键 |
| user_id | BIGINT UNSIGNED | 所属 `users.id` |
| name | VARCHAR(50) | trim 后非空 |
| description | VARCHAR(200) NULL | 默认 NULL |
| created_at | DATETIME(3) | 创建 UTC 时间 |
| updated_at | DATETIME(3) | 最后更新 UTC 时间 |

约束与索引：

- `PRIMARY KEY(id)`
- `FOREIGN KEY(user_id) REFERENCES users(id)`
- `UNIQUE uk_habits_user_name(user_id, name)`，保证同一用户内习惯名称唯一
- `UNIQUE uk_habits_user_id_id(user_id, id)`，用于 `checkin_records` 的复合外键
- `INDEX idx_habits_user_created(user_id, created_at, id)`，用于当前用户列表稳定排序

不同用户允许同名；同一用户名称唯一，由 `UNIQUE uk_habits_user_name(user_id, name)` 保证。名称按当前 `utf8mb4_0900_ai_ci` 排序规则比较（不区分大小写和重音），业务层先 trim 再写入。

## 4. checkin_records

| 字段 | 类型 | 默认/含义 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | 自增主键 |
| user_id | BIGINT UNSIGNED | 打卡用户 |
| habit_id | BIGINT UNSIGNED | 打卡项 |
| checkin_date | DATE | 后端确定的业务日期 |
| checked_in_at | DATETIME(3) | 后端捕获的 UTC 打卡时间 |

约束与索引：

- `PRIMARY KEY(id)`
- **`UNIQUE uk_checkin_user_habit_date(user_id, habit_id, checkin_date)`**
- `FOREIGN KEY(user_id, habit_id) REFERENCES habits(user_id, id)`

唯一约束保证同用户、同习惯、同业务日期最多一条打卡记录，是重复点击和并发请求的最终正确性保证。

复合外键同时保证习惯存在且记录中的 `user_id` 必须与习惯所有者一致。

外键采用 `ON DELETE RESTRICT / ON UPDATE RESTRICT`。本期无删除功能，不使用级联删除清除历史打卡记录。

## 5. 事务与并发写入

每日打卡的基本流程：

1. 从 Session 获取当前用户 ID。
2. 查询习惯并确认属于当前用户。
3. 计算本次请求的业务日期 D。
4. 在 MySQL 事务中尝试插入打卡记录。
5. 插入成功则提交事务并返回 `created=true`。
6. 若命中 `uk_checkin_user_habit_date`，结束失败事务后重新读取该日已有记录，返回 `created=false`。
7. 其他数据库错误按真实异常处理，不能当作重复打卡成功。

应用层可以在插入前查询是否已打卡以优化正常重复请求，但该查询不能替代数据库唯一约束。

打卡成功后的今日状态与连续天数应基于 MySQL 真实记录计算；不得使用可能过期的 Redis 值决定本次写操作结果。

## 6. Redis Key 设计

统一前缀：

```text
checkin:v1
```

其中 `uid` 来自服务端 Session，`hid` 必须完成资源归属校验，`D` 为后端确定的业务日期 `YYYY-MM-DD`。

| 用途与 Key | Value | TTL | 写入时机 | 删除/更新时机 | Miss 处理 |
| --- | --- | --- | --- | --- | --- |
| 登录态 `checkin:v1:session:{tokenSha256}` | 当前实现：十进制用户 ID 字符串，如 `1`，不是 JSON | 默认 7200 秒，固定过期，读取不续期 | 用户名密码验证成功后，同时写入值和 TTL；写入成功才返回 Token | 自动过期；登出删除当前令牌对应的键 | 不能从 MySQL 恢复原 Session；返回 401，用户重新登录 |
| 今日状态 `checkin:v1:today:{uid}:{hid}:{D}` | JSON：date、checkedIn、recordId、checkedInAt | 默认 30 秒，且不得跨到下一个业务日继续使用 | 查询 Miss 后从 MySQL 回源并写入 | 打卡事务提交后删除；读取不续期 | 查询 MySQL 恢复真实状态 |
| 连续天数 `checkin:v1:streak:{uid}:{hid}:{D}` | JSON：asOfDate、streakDays、streakEndDate | 默认 30 秒，且不得跨到下一个业务日继续使用 | 查询 Miss 后从 MySQL 计算并写入 | 打卡事务提交后删除；读取不续期 | 从 MySQL 倒序读取并重算 |

登录态放 Redis 是为了服务端认证和集中失效；今日状态和连续天数放 Redis 是为了减少重复查询。所有真实业务记录始终保存在 MySQL。

当前仅登录态已接入，today/streak 仍为后续设计。会话摘要为原始 Token 的 SHA-256 小写十六进制字符串；不保存原始 Token、密码、issuedAt 或 expiresAt。有效期由 Redis TTL 管理，配置属性为 `app.session.ttl-seconds`，当前 YAML 使用 `SESSION_TTL_SECONDS` 注入。

同一用户可拥有多个独立会话，登出只删除本次令牌的键。用户已不存在时清理会话仍是待实现要求：目前 `/auth/me` 只返回 401，没有删除键，拦截器也不查询用户表。Redis 故障错误码分类及损坏会话值处理尚待补齐和验证。

## 7. MySQL / Redis 一致性策略

业务缓存采用简单 Cache-Aside：

### 查询

```text
Redis Hit
  -> 直接返回缓存结果

Redis Miss
  -> 查询 MySQL
  -> 写入短 TTL Redis 缓存
  -> 返回结果
```

### 打卡写入

```text
MySQL 事务写入并提交
  -> 删除 today 缓存
  -> 删除 streak 缓存
  -> 返回基于 MySQL 的真实结果
```

原则：

1. 不得先写业务缓存再提交 MySQL 事务。
2. MySQL 事务回滚时不得发布“已打卡”缓存。
3. 缓存删除失败时记录脱敏日志；可以进行一次简单重试，但不得为了删缓存回滚已经成功的 MySQL 事务。
4. 删除失败由短 TTL 自动收敛。本期不承诺 MySQL 与 Redis 强一致。
5. 已完成鉴权后，如果仅业务缓存 Redis 操作失败，可直接查询 MySQL。
6. Redis Session 的故障与业务缓存故障不同：无法完成认证时不能绕过登录。
7. 日期进入下一业务日后使用新的日期 Key，上一日缓存不得用于新一天。

本期不实现复杂的“旧读回填竞争控制”、慢查询特殊 TTL 算法、分布式锁或跨 MySQL/Redis 事务。
