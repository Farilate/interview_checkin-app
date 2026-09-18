# interview_checkin-app

面试项目：基于UniApp+Spring Boot的全栈打卡应用

## 当前进度

阶段 3：在 MySQL 持久层基础上，已添加统一响应、参数校验、分页 DTO、全局异常处理和容器错误兜底，尚无登录/习惯/打卡业务接口。
Redis、登录、打卡业务和前端按 [实施计划](docs/IMPLEMENTATION_PLAN.md) 在后续阶段实现。

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

默认监听 8080；控制台出现 `Started CheckinApplication` 表示启动成功。当前访问 `/` 返回 404 属正常现象，尚未实现业务路由。Ctrl+C
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
| `APP_BUSINESS_ZONE` | `Asia/Shanghai` | 预留业务时区配置；阶段 6 接入 Clock，不改变 JVM 时区 |

例如在同一 PowerShell 窗口执行后启动：

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

阶段 2 已接入 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`；Redis 连接及认证、Session/缓存 TTL、CORS
和演示账户变量在后续阶段接入，遵循 [架构配置约定](ARCHITECTURE.md)。真实凭证只在运行环境中注入，不能提交到 Git；本地 `.env*`
和 `application-local.*` 已忽略。

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
userId。用户名小写规范化将在账户初始化/登录阶段处理；数据库采用 ASCII 二进制排序规则进行精确比较。

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

普通 `clean verify` 执行阶段 3 Web 契约测试并打包，不执行 `PersistenceIT`。报告位于 `backend/target/failsafe-reports/`
。当前测试覆盖三个 Mapper、字段映射、分页与用户隔离、用户名唯一、同用户习惯名称唯一、跨用户同名允许、打卡唯一和复合外键。并发与
HTTP 业务需在后续阶段单独验证。

## 当前技术与业务约定

- Java 版本统一为 21；本机 Microsoft JDK 路径为 `C:\Users\Farilate\.jdks\ms-21.0.12.1`，IDEA SDK、Maven JDK 与 `JAVA_HOME`
  均应选择它。
- Model 使用 Lombok `@Getter`、`@Setter`，Maven 显式配置注解处理器，版本由 Spring Boot 管理。重新导入 Maven 项目即可获取依赖；IDEA
  编译需要启用注解处理。配置依据：[Lombok Maven 说明](https://projectlombok.org/setup/maven)。
- 不同用户允许同名习惯，同一用户内名称唯一；数据库约束为 `uk_habits_user_name(user_id, name)`。名称先 trim，并按数据库
  `utf8mb4_0900_ai_ci` 排序规则判重，同用户冲突返回 HTTP 409 / `40901`。
- 已有旧表不会因修改建表脚本自动升级；应核对现有库已包含该唯一索引。本次不重复修改用户已经更新的数据库。
- MySQL 本机版本为 26.7.0；Redis、前端及完整业务联调未在本次验证。此前测试记录见 `docs/TEST_PLAN.md`，旧规则的结果不能替代当前规则验收。
  最新验证：Java 21 下执行 `-Pmysql-it clean verify` 构建成功，真实 MySQL 上 10 个持久层测试全部通过（包含新的习惯名称唯一规则）；测试数据全部回滚，详见
  docs/TEST_PLAN.md 第 12 节。

## 阶段 3 Web 验证

在 `backend/` 使用 Java 21 执行 `mvnw.cmd -B -ntp clean verify`，会运行 `ApiContractTest`。该测试启动随机端口的真实 HTTP
容器，装配生产的异常处理、响应通知和 JSON 配置，不连接 MySQL/Redis。测试专用接口不会打包到生产应用。

本次 36 项契约检查全部通过，0 失败/错误/跳过，构建成功。覆盖成功与
201、空数据、全部业务错误码、请求体和参数校验、未知字段、404/405/406/415、容器错误分派、数据库故障分类及敏感信息不泄露。报告位于
`backend/target/surefire-reports/`。本次没有重跑数据库集成测试，也没有验证登录、Redis 或前端业务。
