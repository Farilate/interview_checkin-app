# interview_checkin-app

面试项目：基于UniApp+Spring Boot的全栈打卡应用

## 当前进度

阶段 1–3 的项目骨架、MySQL 持久层及统一响应已具备；阶段 4 已新增登录、查询当前用户、登出、Redis Session 和演示账户初始化代码，尚未完成登录模块验收。
习惯、打卡业务及前端仍按 [实施计划](docs/IMPLEMENTATION_PLAN.md) 后续实现。当前接口契约见 [API 文档](docs/API.md)。

## 后端环境与启动

- JDK：固定 Java 21 编译目标，本机使用 Microsoft JDK 21（IDEA SDK：`ms-21`）。
- Spring Boot：4.1.1（[官方兼容说明](https://docs.spring.io/spring-boot/4.1/system-requirements.html)支持 Java 21）。
- Maven：Wrapper 固定 3.9.11；Wrapper 脚本版本 3.3.4，无需全局安装 Maven。
- 持久层：MyBatis Spring Boot Starter 4.1.0，MySQL Connector/J 版本由 Spring Boot 管理。

设置 `JAVA_HOME` 为 JDK 21 安装目录，并将其 `bin` 加入 PATH。首次构建需联网下载 Maven 和依赖，默认缓存到用户目录 `.m2/`。

在仓库根目录打开 PowerShell：

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\Farilate\.jdks\ms-21.0.12.1' # 按实际 JDK 21 路径修改
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean verify
# 启动前按下文初始化数据库并设置 DB_URL、DB_USERNAME、DB_PASSWORD。
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
`com.example.checkin.CheckinApplication`。

## 环境变量

配置来自启动进程继承的操作系统环境变量，或 IDEA Run Configuration 的 Environment variables。本项目不会自动加载 `.env`。

| 变量                | 默认值          | 当前用途                                             |
|---------------------|-----------------|------------------------------------------------------|
| `JAVA_HOME`         | 无              | Maven 使用的 JDK 21 路径                             |
| `DB_URL`            | 无，必填        | MySQL JDBC URL，包含明确的数据库名                   |
| `DB_USERNAME`       | 无，必填        | 数据库用户                                           |
| `DB_PASSWORD`       | 无，必填        | 数据库密码，仅从运行环境注入                         |
| `SERVER_PORT`       | `8080`          | HTTP 监听端口                                        |
| `REDIS_HOST` | `localhost` | 登录会话 Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `SESSION_TTL_SECONDS` | `7200` | 会话固定有效期，单位秒，应为正数；读取不续期 |
| `SPRING_PROFILES_ACTIVE` | `local` | 当前默认加载 local 配置，可由运行环境覆盖 |

例如在同一 PowerShell 窗口执行后启动：

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

Redis 地址和 Session TTL 已接入。Redis 认证及库编号可通过 Spring 配置属性 `spring.data.redis.username`、`spring.data.redis.password`、`spring.data.redis.database` 提供，或使用对应标准环境变量 `SPRING_DATA_REDIS_USERNAME`、`SPRING_DATA_REDIS_PASSWORD`、`SPRING_DATA_REDIS_DATABASE`；当前 YAML 未映射简写变量 `REDIS_USERNAME`、`REDIS_PASSWORD`、`REDIS_DATABASE`。业务缓存 TTL、CORS 和业务时区运行逻辑尚未接入。

演示账户读取 `app.demo-user.username`、`app.demo-user.password`，可在 Git 忽略的 `application-local.yml` 中配置；当前未映射 `DEMO_USERNAME`、`DEMO_PASSWORD`。两者均非空白时，应用启动会自动创建不存在的账户；已有账户不会重设密码，未配置则跳过。密码只以 BCrypt 哈希写入 MySQL。初始化器没有独立开关，测试若启动完整应用也可能触发，因此测试环境应留空演示凭证。真实凭证不得提交到 Git；本地 `.env*` 和 `application-local.*` 已忽略。

## 登录、当前用户和登出

启动前准备已建表的 MySQL、可访问的 Redis 及已有账户或演示账户配置。以下是当前后端行为，不代表已通过联调：

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
UTC 填入，`LocalDate` 保存业务日期。Mapper 使用参数绑定；习惯和打卡记录查询均包含用户 ID，后续 Service 必须传入服务端身份，不能信任前端
userId。账户初始化和登录已执行用户名 trim 及小写规范化；用户名限制为 3–32 位 ASCII 字母、数字或下划线，密码限制为 8–72 个 UTF-8 字节。数据库采用 ASCII 二进制排序规则进行精确比较。

## Phase 2 集成测试

当前 `PersistenceIT` 使用 `local` profile，连接已初始化三张表的专用测试数据库。每个测例由 Spring
开启事务并在结束后回滚，可重复执行；不会自动建库、建表，也不包含多线程并发测试。

1. 创建专用测试库，并在其中执行 `database/init.sql`。
2. 在 Git 忽略的 `backend/src/main/resources/application-local.yml` 配置测试数据源，或通过 `SPRING_DATASOURCE_URL`、
   `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 环境变量覆盖数据源。不要将测试指向业务数据库，真实密码不得提交。
3. 使用 JDK 21，在 `backend/` 执行：

```powershell
.\mvnw.cmd -B -ntp -Pmysql-it clean verify
```

普通 `clean verify` 执行 56 项公共 HTTP 契约检查和 25 项认证回归并打包，不执行 `PersistenceIT`；报告位于 `backend/target/surefire-reports/`。启用 `mysql-it` 后额外运行 10 项真实 MySQL 持久层测试，报告位于 `backend/target/failsafe-reports/`。

持久层测试覆盖三个 Mapper、字段映射、分页、用户隔离、用户名唯一、同用户习惯名称唯一、跨用户同名允许、打卡唯一和复合外键，不代表业务接口或 HTTP 并发验收已完成。测试前应留空演示账户初始化凭证。

## 当前技术与业务约定

- Java 版本统一为 21；本机 Microsoft JDK 路径为 `C:\Users\Farilate\.jdks\ms-21.0.12.1`，IDEA SDK、Maven JDK 与 `JAVA_HOME`
  均应选择它。
- Model 使用 Lombok `@Getter`、`@Setter`，Maven 显式配置注解处理器，版本由 Spring Boot 管理。重新导入 Maven 项目即可获取依赖；IDEA
  编译需要启用注解处理。配置依据：[Lombok Maven 说明](https://projectlombok.org/setup/maven)。
- 不同用户允许同名习惯，同一用户内名称唯一；数据库约束为 `uk_habits_user_name(user_id, name)`，按 `utf8mb4_0900_ai_ci` 排序规则判重。
- 已有表不会随建表脚本变更自动升级，应确认数据库已包含约定的唯一索引。
- 习惯名称 trim 和 HTTP 409 / `40901` 属于 Phase 5 的业务接口要求；当前已落实数据库约束。

## 异常处理约定

业务层明确指定 ErrorCode，MVC 已知异常按类型映射，未知异常返回 50001；不从 HTTP 状态反推业务原因。Redis 会话故障保持 50301，数据库可用性故障保持 50302。容器错误分派和日志脱敏规则详见 [API 文档](docs/API.md)。

## 验证结果与限制

2026-09-18，Java 21.0.12.1 / Maven 3.9.11 下异常映射重构后的 `verify` 构建成功：81 项测试全部通过，0 失败、0 错误、0 跳过。其中公共 HTTP 契约测试 56 项、认证回归 25 项；认证回归使用外部存储替身。

真实 MySQL 的 10 项持久层测试在此前回归中通过，认证修复后未重跑。真实 MySQL/Redis 登录联调、实际会话到期、断网故障、演示账户并发初始化和 H5 尚未验收，阶段 4 因此尚未完成全部验收。CORS 留待 H5 联调阶段处理。

测试覆盖、报告位置和剩余验收项统一维护在 [测试计划](docs/TEST_PLAN.md) 第 9–10 节。
