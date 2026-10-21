# R1 Identity — 首个 slice 合同与 Human Core

## 决定与当前 Gate

- Date: 2026-10-21
- Slice: R1-A — 独立 Identity 账户原子持久化，包含默认 Language 授权。
- Gate: REVIEW_PENDING；R1-A 验收通过，待完整代码导读和用户 Critical Review；不进入 READY_TO_COMMIT。
- 用户已确认本 slice、首次骨架预算例外及由用户实现事务方法，并要求增加便于阅读的中文业务注释。
- Architecture / Scope / Commit 仍由用户负责，不自动开始下一 slice。
- Git baseline: `architecture/microservices-migration` / `f5a729a`，与合并后的 main 一致。
- 上位决定：[ADR-0005](../adr/0005-unified-learning-platform-microservices.md)、
  [平台方案 C24–C28 / §4.1 / §9](../architecture/PLATFORM_ARCHITECTURE_PROPOSAL.md)。

## 目标、边界与取舍

Identity 独立为一个 Maven 运行模块，使用独立数据库和专用账号。
内部 Java 方法成功时创建一个完整账户：账户、LOCAL_EMAIL 身份、password verifier 和 LANGUAGE 准入一起提交。
任何一步失败都不能返回成功或留下部分账户；重复邮箱由数据库唯一约束裁决。

选择先验证小事务，避免一次搬迁注册 HTTP、密码策略、限流、Session、Cookie、CSRF 和跨服务身份链。
代价是本 slice 完成后仍不能通过浏览器注册或登录，不代表 R1 整体完成。

Non-scope：注册 / 登录 / 退出 / Session、Redis、Dubbo / Nacos / mTLS、Gateway、业务消费者、管理员指定、
账号停用及授撤权 API、旧数据迁移、前端、S11 剩余功能、SAA / 模型、生产部署、仓库重命名与 commit / push。
C31 工程和补丁已按用户要求删除，本 slice 不恢复它们；未来补丁必须重新给出用途、影响和替代方案供用户决定。

## 目录、构建与安全边界

- 根 `pom.xml`：Java 25、Boot 4.1.1、Cloud 2025.1.3 BOM；只聚合 `services/identity`。
- 服务坐标：`com.dailylanguage:identity-service:0.0.1-SNAPSHOT`，应用名 `identity-service`。
- 旧 `server/` / `client/` 保持原样；旧 server 仍是未切换的迁移来源，不是新平台第二套账户 authority。
- 无服务实现 JAR 依赖、公共认证模块、空 RPC 合同、生产补丁或框架替换。
- 主要 production Java 文件为 5 个；首次骨架、POM、SQL、XML、配置的总预算例外已获用户接受。
- 仅允许无组件详情的健康检查；其他请求拒绝，不生成框架默认账户、不启用 Basic / 表单登录或 Session。
- 保留写请求 CSRF 拒绝；CSRF 使用 Cookie 仓库，避免默认仓库提前创建 HttpSession。
- 数据源必须显式提供，不能回退到旧 Language 库。新环境详见 [本地验证说明](../../deploy/local/identity/README.md)。

## 数据与事务合同

| 数据 | 当前规则 |
|---|---|
| app_user | 数据库生成 UUIDv7，外部不能指定 userId；账号停用模型留后续合同 |
| auth_identity | 仅 LOCAL_EMAIL；provider + normalized subject 唯一 |
| local_password_credential | 只存 verifier；存储格式检查不代表完整密码 policy 或 Argon2 验证 |
| app_access_grant | userId + appCode 唯一；注册入口只创建 LANGUAGE，不授予 PERSONAL_NOTE 或管理员资格 |

所有外键留在 Identity 库内，不建立跨库事务或外键，不复制语言档案、业务表、singleton slot 或 vector extension。
公开注册能力后续才会迁移邮箱 policy、密码 policy 和受限 Argon2；散列在数据库事务外完成。
当前内部方法的参数是已规范化邮箱和已编码 verifier，不能成为接收客户端 hash 的 HTTP / RPC 接口。
新建账户默认授权只发生一次，后续登录不得补回已撤销权限。
数据库提交确认丢失可能产生 UNKNOWN，不自动推断回滚或重放创建。
不得记录邮箱、密码、verifier 或可能包含这些值的数据库异常链。

## Human Core：用户实现边界

文件：`services/identity/src/main/java/com/dailylanguage/identity/account/LocalAccountPersistence.java`

方法：`createAccountWithLanguageAccess(String normalizedEmail, String encodedPasswordHash)`。

骨架先提供签名、业务注释及明确失败的占位；用户已在提示和增量 Review 后完成事务方法。
用户加入 `@Transactional`、四次 Mapper 调用和成功返回，随后修正占位异常、异常打印及过期 TODO。
这属于用户 hands-on（提示后实现），不记作无提示独立实现，也不因测试通过自动提高 Ownership。
`LocalAccountMapper` 已提供所需四个 SQL 操作；不需要新增抽象层或修改 Mapper 来完成正常事务编排。
调用者经 Spring 管理的 bean 进入，不能依赖测试事务或直接 `new` 对象获得原子性。

Acceptance Criteria：

1. 返回持久化 userId，四部分数据完整且只获得 LANGUAGE。
2. 并发使用相同邮箱时恰好一个成功，另一个为唯一冲突，无孤立 app_user。
3. 凭据写入或最后一步授权写入实际失败时，外层账户写入全部回滚，失败向调用者传播。
4. 不捕获异常后继续返回成功，不加入自动重试、Session、任意 appCode 或管理员引导逻辑。

事务方法的增量 Review 已无阻塞项，4 项行为测试及当前候选的集中验收均通过；下一步为完整导读与用户 Review。
求助按“定位 / 思路 → 必要时伪代码 / 局部示例”逐级进行；用户明确要求示范或转交后才完整代写。
不将 Codex 编码或测试通过记作用户 hands-on / Ownership PASS。

## 分工与 Learning Focus

| 作者 | 工作 |
|---|---|
| 用户 | 事务方法、关键失败判断与代码 Review |
| Codex | 构建、安全骨架、Mapper、migration、中文业务注释、环境、Java 测试与实际验证 |

Learning Focus：Spring 事务代理怎样覆盖多次 SQL；数据库唯一约束如何裁决并发，以及应用准入与业务归属的区别。
不安排用户编写 Python 验证脚本。

## Verification

本节仅记录此新服务的证据，不沿用 C31 的历史通过作为 Identity 兼容结论。
初次离线构建因依赖尚未缓存失败，随后在不改变版本的情况下下载目标依赖。
第一次 HTTP 测试 8/9 通过，健康检查因过度限定整个 JSON 字符串而失败；
随后改为断言 UP 且无 components / details，允许框架公开的探针组名称。

PRIOR — 本 slice 前期骨架证据：HTTP 9/9、Mapper / schema / 账号边界 5/5 PASS；Human Core 占位时为
2 failures / 2 errors。第一版用户实现保留了成功路径的占位异常，回滚测试 2 项 PASS、成功相关测试 2 项 ERROR；
去除占位异常及不安全的异常打印后，用户事务方法 4/4 PASS。以上历史不代替本次集中验收。

FRESH 2026-10-21 — 当前候选验收：

| 检查 | 结果与证明边界 |
|---|---|
| 工具与目标依赖 | Temurin 25.0.4+7、Maven 3.9.12；Boot 4.1.1、Security 7.1.1、Spring Context 7.0.9、MyBatis-Plus 3.5.17、MyBatis 3.5.19、PostgreSQL JDBC 42.7.13；Cloud 2025.1.3 仅导入 BOM |
| 集中测试与打包 | HTTP 9 + Mapper / schema / 账号边界 5 + 用户事务 4，共 18/18 PASS，0 failures / errors / skipped；同一次 Maven package 成功 |
| 事务与并发 | 正常创建完整账户、并发相同邮箱恰好一个成功、凭据 / 最后一步授权实际 SQL 失败时整笔回滚均通过；测试没有替 production bean 提供外层事务 |
| 真实应用重启 | 同一 JAR 的两个独立 JVM（PID 35365、36441）均 health 200 / UP；两次启动均确认 Flyway V1 已应用、无需重复迁移 |
| 持久化一致性 | 使用 identity_app 比较启动前及两次启动后的四表逐行脱敏摘要，完全一致；app_user / auth_identity / credential / grant 各 4 条，LANGUAGE 4、PERSONAL_NOTE 0 |
| 数据来源与环境 | 隔离 PostgreSQL 18.6；验收前已有 2 个合成账户，本轮测试新增 2 个完整合成账户；没有连接旧业务库或清库，未遗留失败注入 trigger |
| 候选一致性与清理 | 运行前后 source 摘要一致，两次启动 JAR 摘要一致；两个临时应用 JVM 已正常停止，隔离数据库保留 |

本轮命令（先加载仓库外的私有环境文件）：

```sh
mvn -q package -o -pl services/identity -am -Dtest=IdentityBoundaryHttpTests,IdentityMapperIntegrationTests,LocalAccountPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

运行报告位于忽略的 `services/identity/target/surefire-reports/`；本轮日志为 `target/r1-acceptance.log`、
`target/r1-start-1.log`、`target/r1-start-2.log`，source / JAR 摘要及三份逐行数据摘要也在 target 中。
数据摘要仅包含表名、内部记录标识和行摘要，不保存邮箱或 verifier 原文。实际密码及运行产物不提交。
重启验收写入端是 production 事务 bean，读取端是 Identity 专用账号的 SQL；当前没有公开账户读取 API。
此处验证的是应用 / JVM 重启，未重启数据库容器或模拟数据库崩溃。未设置数据库测试开关产生的 SKIPPED 不能算通过。

非阻塞 Review 项：当前 POM 额外显式声明 `spring-context:7.0.9`，与 Boot 4.1.1 BOM 的实际管理版本一致，
本次未出现版本偏移；重复固定版本会影响未来统一升级，是否去除显式声明留给 Review，本轮保留原样。

NOT_RUN / 尚未证明：真实密码 policy / Argon2 验证、完整注册 HTTP、Session、跨服务认证 / 授权、
Dubbo 原版兼容、Cloud runtime、SAA、旧业务回归、前端、生产部署、数据库故障恢复、提交确认丢失及外层事务传播场景。

Acceptance: PASS（仅 R1-A 本地内部账户持久化）。[已实现事务 Flow](../flow/identity-account-persistence.md) 已按当前 source / tests 核对。
Codex 自查不替代用户 Critical Review；单一 Next Action 为当前 R1-A 代码导读与用户 Review，不提高 Ownership，不进入下一 slice。
