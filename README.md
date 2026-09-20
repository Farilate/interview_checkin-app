# interview_checkin-app

面试项目：基于UniApp+Spring Boot的全栈打卡应用

## 当前进度

阶段 1–8 的开发及真实验收均已完成（2026-09-20），覆盖项目骨架、MySQL 持久层、统一响应、登录与登出、Redis Session、Habit 创建/去重/分页、每日打卡与并发安全、今日状态、连续天数及 Redis 业务缓存。阶段 9–11 的前端、前后端联调与最终交付仍按 [实施计划](docs/IMPLEMENTATION_PLAN.md) 后续开展。当前接口契约见 [API 文档](docs/API.md)。

## 后端环境与启动

- JDK：固定 Java 21 编译目标，本机使用 Microsoft JDK 21（IDEA SDK：`ms-21`）。
- Spring Boot：4.1.1（[官方兼容说明](https://docs.spring.io/spring-boot/4.1/system-requirements.html)支持 Java 21）。
- Maven：Wrapper 固定 3.9.11；Wrapper 脚本版本 3.3.4，无需全局安装 Maven。
- 持久层：MyBatis Spring Boot Starter 4.1.0，MySQL Connector/J 版本由 Spring Boot 管理。

设置 `JAVA_HOME` 为 JDK 21 安装目录，并将其 `bin` 加入 PATH。首次构建需联网下载 Maven 和依赖，默认缓存到用户目录 `.m2/`。

在仓库根目录打开 PowerShell：

```powershell
cd backend
$env:JAVA_HOME = 'C:\path\to\jdk-21' # 按实际 JDK 21 路径修改
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean verify
# 启动前按下文初始化数据库，通过环境变量或外部本地配置提供数据库连接。
.\mvnw.cmd spring-boot:run
```

也可以在构建后执行：

```powershell
java -jar target/checkin-0.0.1-SNAPSHOT.jar
```

macOS/Linux 使用 JDK 21，在 `backend/` 执行 `sh ./mvnw clean verify` 和 `sh ./mvnw spring-boot:run`。

默认监听 8080；控制台出现 `Started CheckinApplication` 表示启动成功。当前访问 `/` 返回 404 属正常现象，认证接口位于 `/api/v1/auth`。Ctrl+C
停止进程。

IDEA：将 `backend/pom.xml` 添加为 Maven 项目，项目 SDK、Maven 导入及运行 JDK 均选择 21；Maven 使用 Wrapper；运行
`com.example.checkin.CheckinApplication`，Working directory 设置为项目的 `backend/`。

## 环境变量

配置可来自进程环境变量（包括 IDEA Run Configuration 的 Environment variables），也可来自外部 `backend/config/application-local.yml`。本项目不会自动加载 `.env`。下表描述默认 application.yml 的环境变量映射；若外部 local 文件直接设置对应的 `spring.datasource.*` 属性，则无需重复提供 DB_* 变量。

| 变量                | 默认值          | 当前用途                                             |
|---------------------|-----------------|------------------------------------------------------|
| `JAVA_HOME`         | 无              | Maven 使用的 JDK 21 路径                             |
| `DB_URL`            | 无，必填        | MySQL JDBC URL，包含明确的数据库名                   |
| `DB_USERNAME`       | 无，必填        | 数据库用户                                           |
| `DB_PASSWORD`       | 无默认密码        | 通过环境变量注入，或在不提交、不打包的外部 local 配置中设置 `spring.datasource.password` |
| `SERVER_PORT`       | `8080`          | HTTP 监听端口                                        |
| `REDIS_HOST` | `localhost` | 登录会话 Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `APP_CACHE_TTL_SECONDS` | `30` | today/streak 缓存 TTL 上限（秒）；与距下一业务日零点的时长取较小值 |
| `APP_BUSINESS_ZONE` | `Asia/Shanghai` | 已接入 app.business-zone，打卡日期、今日状态和连续天数统一使用 |
| `SESSION_TTL_SECONDS` | `7200` | 会话固定有效期，单位秒，应为正数；读取不续期 |
| `SPRING_PROFILES_ACTIVE` | `local` | 当前默认加载 local 配置，可由运行环境覆盖 |

例如在同一 PowerShell 窗口执行后启动：

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

Redis 地址和 Session TTL 已接入。Redis 认证及库编号可通过 Spring 配置属性 `spring.data.redis.username`、`spring.data.redis.password`、`spring.data.redis.database` 提供，或使用对应标准环境变量 `SPRING_DATA_REDIS_USERNAME`、`SPRING_DATA_REDIS_PASSWORD`、`SPRING_DATA_REDIS_DATABASE`；当前 YAML 未映射简写变量 `REDIS_USERNAME`、`REDIS_PASSWORD`、`REDIS_DATABASE`。业务时区已由 TimeConfig 接入，app.business-zone 默认 Asia/Shanghai；业务缓存 TTL 已接入 APP_CACHE_TTL_SECONDS，默认 30 秒且不超过下一业务日零点；CORS 尚未接入。

真实本地配置放在 `backend/config/application-local.yml`，不再放进 `src/main/resources/`。可复制 [配置示例](backend/config/application-local.example.yml) 后填写；真实文件由 Git 忽略，也不参与 Maven 打包。示例通过 `${DB_PASSWORD}` 引用环境变量；个人 local 文件也可以直接填写 `spring.datasource.password`，但不得提交或分发。若要用环境变量强制覆盖 local 中的直接配置，使用 `SPRING_DATASOURCE_PASSWORD`。应用启动不创建用户，原演示初始化配置已取消。

启动 Maven、JAR 或 IDEA 时均以 `backend/` 为工作目录，Spring Boot 按激活的 profile 从默认搜索路径读取外部 `./config/`，application.yml 不再显式导入 local 文件；test profile 不加载 application-local.yml。若必须从仓库根目录启动，可设置 `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:./backend/config/`。复制示例后填写数据库配置，或继续使用环境变量。打包分发时仅分发 JAR 与无秘密示例，不包含真实本地配置。

## 演示账户准备

在已执行 `database/init.sql` 的本地或隔离演示库中，手工执行 [database/demo-data.sql](database/demo-data.sql)。它只保存 BCrypt 哈希；已有 `demo_user` 时跳过，不覆盖密码。脚本按单次手工执行设计，不作为并发注册接口。

公开演示凭证：用户名 `demo_user`，密码 `DemoOnly123!`。这是可公开的本地演示数据，不能用于生产或复用为真实账户密码；已有同名账户时继续使用其原密码。

正式注册已纳入[实施计划第 6 节](docs/IMPLEMENTATION_PLAN.md)，作为主线 Phase 1–11 全部完成后最后考虑的可选加分项，不属于主线开发或必做验收，当前不提供注册 API。是否公开注册、注册后是否自动登录及响应契约仍待实施前确定。

## 登录、当前用户和登出

启动前准备已建表的 MySQL、可访问的 Redis 及已有账户或手工导入的演示账户。以下是当前后端行为，不代表已通过联调：

1. `POST /api/v1/auth/login`：提交 JSON 用户名和密码，成功返回 `data.token`、`tokenType`、`expiresIn` 和 `user`。用户名 trim 后转小写，密码不 trim。
2. `GET /api/v1/auth/me`：携带 `Authorization: Bearer <token>`，返回 `data.id` 和 `data.username`；ID 为十进制字符串。
3. `POST /api/v1/auth/logout`：携带相同请求头，无需请求体，成功返回 `{"code":0,"message":"ok","data":null}`。
4. 登出后该令牌再次访问受保护接口或再次登出返回 HTTP 401 / `40101`；同一用户的其他令牌仍有效。

Redis 会话键为 `checkin:v1:session:{tokenSha256}`，值是十进制用户 ID 字符串，默认固定 7200 秒过期。会话不存在时需要重新登录，不能从 MySQL 恢复令牌。当前尚无 H5 跨域配置，跨域联调前需补齐。

## MySQL 初始化与持久层

使用 MySQL 8.0+。由数据库管理员创建空数据库和专用应用用户，再用 MySQL
客户端显式选中该库运行 [database/init.sql](database/init.sql)。脚本不创建数据库、不删除表、不插入演示账户；只在空库执行一次，应用启动不会自动运行
SQL。

例如在 MySQL 客户端中（路径按仓库位置修改）：

```text
CREATE DATABASE checkin CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE checkin;
SOURCE C:/path/to/interview_checkin-app/database/init.sql;
```

启动前在 PowerShell 中注入配置（使用自己的数据库用户）：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/checkin?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = Read-Host '数据库用户名'
$credential = Get-Credential -UserName $env:DB_USERNAME -Message '输入数据库密码'
$env:DB_PASSWORD = $credential.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run
```

IDEA 运行时在 Environment variables 中配置相同变量。MySQL 会话时区设为 UTC；Model 中 `LocalDateTime` 字段须由后续业务层按
UTC 填入，`LocalDate` 保存业务日期。Mapper 使用参数绑定；习惯和打卡记录查询均包含用户 ID，Habit Service 已传入服务端身份，后续业务同样必须遵守，不能信任前端
userId。登录已执行用户名 trim 及小写规范化；用户名限制为 3–32 位 ASCII 字母、数字或下划线，密码限制为 8–72 个 UTF-8 字节。数据库采用 ASCII 二进制排序规则进行精确比较。

## Phase 2 集成测试

当前 `PersistenceIT` 使用 `local` profile，连接已初始化三张表的专用测试数据库。每个测例由 Spring
开启事务并在结束后回滚，可重复执行；不会自动建库、建表，也不包含多线程并发测试。

1. 创建专用测试库，并在其中执行 `database/init.sql`。
2. 在 Git 忽略的 `backend/config/application-local.yml` 配置测试数据源，或通过 `SPRING_DATASOURCE_URL`、
   `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 环境变量覆盖数据源。不要将测试指向业务数据库，真实密码不得提交。
3. 使用 JDK 21，在 `backend/` 执行：

```powershell
.\mvnw.cmd -B -ntp -Pmysql-it clean verify
```

普通 `clean verify` 由 Surefire 执行 214 项回归并打包，不执行真实 MySQL 测试。启用 mysql-it 后，Failsafe 额外执行 PersistenceIT（10 项）和 CheckinConcurrencyIT（1 项真实 MySQL 10 线程并发）。报告分别位于 backend/target/surefire-reports/ 与 failsafe-reports/。

持久层测试覆盖三个 Mapper、字段映射、分页、用户隔离、用户名唯一、同用户习惯名称唯一、跨用户同名允许、打卡唯一和复合外键，其自动化覆盖范围不包含业务接口或 HTTP 并发；这些真实验收已完成。应用启动不再执行账户写入，测试仍必须指向专用测试库。

## 当前技术与业务约定

- Java 版本统一为 21；JAVA_HOME、IDEA Project SDK 和 Maven JDK 均使用 JDK 21。
- Model 使用 Lombok `@Getter`、`@Setter`，Maven 显式配置注解处理器，版本由 Spring Boot 管理。重新导入 Maven 项目即可获取依赖；IDEA
  编译需要启用注解处理。配置依据：[Lombok Maven 说明](https://projectlombok.org/setup/maven)。
- 不同用户允许同名习惯，同一用户内名称唯一；数据库约束为 `uk_habits_user_name(user_id, name)`，按 `utf8mb4_0900_ai_ci` 排序规则判重。
- 已有表不会随建表脚本变更自动升级，应确认数据库已包含约定的唯一索引。
- Habit 创建返回 201，名称 trim 后按 Unicode 码点校验并查重；仅指定名称唯一约束冲突转 HTTP 409 / `40901`。空白描述转 NULL；创建与分页响应均输出字符串 ID 和带 Z 的 UTC 时间。分页只查询当前用户，成功返回 200。

## 异常处理约定

业务层明确指定 ErrorCode，MVC 已知异常按类型映射，未知异常返回 50001；不从 HTTP 状态反推业务原因。Redis 会话故障保持 50301，数据库可用性故障保持 50302。容器错误分派和日志脱敏规则详见 [API 文档](docs/API.md)。

## 验证结果与限制

2026-09-20，Java 21.0.12.1 / Maven 3.9.11 执行 clean verify：214 项普通测试全部通过，0 失败、0 错误、0 跳过，打包成功。上述自动化缓存测试使用 Redis 替身，未执行 mysql-it；另于 2026-09-20 阶段 1–8 的全部真实验收完成，包括真实 Memurai 的 TTL、Key 内容、写后失效和自然过期。本次文档同步未重跑测试，自动化测试记录保持不变。

真实 MySQL 并发测试确认 10 个 Service 调用仅一次 created=true、返回同一记录且数据库只有一条；固定 Clock 防止跨午夜干扰，测试结束仅清理本次随机用户数据。该 Service 测试与已完成的 HTTP + Redis 鉴权并发验收分别记录。阶段 1–8 的真实 MySQL/Redis 联调、会话到期、故障场景和 SQL 初始化验收已完成；H5 与 CORS 留待阶段 9–11。

测试覆盖、报告位置和剩余验收项统一维护在 [测试计划](docs/TEST_PLAN.md) 第 9–10 节。
