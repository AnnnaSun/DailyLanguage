# R1 Identity — Slice 合同与 Human Core

## 当前 Gate

- Date: 2026-10-31
- Current Slice: R1-G — HTTP 认证与 Session 生命周期；用户明确请求完整示范后，参考 Codex 示范写入登录失败 handler，Codex 完成其余范围。
- Gate: R1-G READY_TO_COMMIT；2026-10-31 用户接受当前实现、完整分层与 Session v2 取舍，Human Review COMPLETE。当前源码与 2026-10-30 的 18/18、77/77 两批及 package PASS 候选完全一致；本轮未重跑测试。R1-G 尚未提交，R1-F COMPLETE / COMMITTED `dc8df93`，不重新打开。
- R1-A 已提交 `9b3ef1a`，R1-B COMPLETE / COMMITTED `3f0d36f`；R1-B 收口文档已提交 `0d69221`。R1-C 从 `architecture/microservices-migration` / `0d69221` 的干净工作区开始。
- 当前基线：2026-10-31 核对 `migrate/identity` / `c916addcd928159fd99dbfe1c49327af8bc0163c`（R1-G 骨架）；当前 src 全部文件 SHA-256 与最终验收摘要一致，无新增或遗漏文件。Cookie 测试修正、完整分层、获准 Session v2 与文档均未提交；旧 hash / namespace 保留为有日期的历史证据，不作为当前路径或会话合同。
- PRIOR：R1-F 2026-10-25 的 80/80 与数据库 10/10 两批、package 与清理证据见下方记录；2026-10-26 Review 收口只核对 Git / source 并同步文档，未重跑测试。更早各 Slice 的有日期证据保留为历史。
- 不自动 commit / push，不提高模块整体 Ownership；R1-G 工程验收与 scoped Human Review 已收口，等待用户 Commit Decision，不开始新的业务 Slice。

## R1-A 决定与交付记录

- Date: 2026-10-21
- Slice: R1-A — 独立 Identity 账户原子持久化，包含默认 Language 授权。
- Gate: COMMITTED；R1-A 验收通过，用户在代码导读与 Review 讨论后已本地提交 `9b3ef1a`。
- 用户已确认本 slice、首次骨架预算例外及由用户实现事务方法，并要求增加便于阅读的中文业务注释。
- Architecture / Scope / Commit 仍由用户负责，不自动开始下一 slice。
- 实现基线：`architecture/microservices-migration` / `f5a729a`，迁移启动时与合并后的 main 一致。
- 实现提交：`9b3ef1a` — `feat(identity): add atomic account creation with language access`，由用户于 2026-10-21 本地提交，共 23 个文件。
- 上位决定：[ADR-0005](../adr/0005-unified-learning-platform-microservices.md)、
  [平台方案 C24–C28 / §4.1 / §9](../architecture/PLATFORM_ARCHITECTURE_PROPOSAL.md)。

## 目标、边界与取舍

Identity 独立为一个 Maven 运行模块，使用独立数据库和专用账号。
内部 Java 方法成功时创建一个完整账户：账户、LOCAL_EMAIL 身份、password verifier 和 LANGUAGE 准入一起提交。
任何一步失败都不能返回成功或留下部分账户；重复邮箱由数据库唯一约束裁决。

选择先验证小事务，避免一次搬迁注册 HTTP、密码策略、限流、Session、Cookie、CSRF 和跨服务身份链。
代价是本 slice 完成后仍不能通过浏览器注册或登录，不代表 R1 整体完成。

Non-scope：注册 / 登录 / 退出 / Session、Redis、Dubbo / Nacos / mTLS、Gateway、业务消费者、管理员指定、
账号停用及授撤权 API、旧数据迁移、前端、S11 剩余功能、SAA / 模型、生产部署、仓库重命名与自动 commit / push。
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

文件：`services/identity/src/main/java/com/dailylanguage/identity/account/infrastructure/LocalAccountPersistence.java`

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

事务方法的增量 Review 已无阻塞项，4 项行为测试及提交候选的集中验收均通过；后续代码导读与 Review 讨论完成后，用户已自主提交 `9b3ef1a`。
求助按“定位 / 思路 → 必要时伪代码 / 局部示例”逐级进行；用户明确要求示范或转交后才完整代写。
不将 Codex 编码或测试通过记作用户 hands-on / Ownership PASS。

## 分工与 Learning Focus

| 作者 | 工作 |
|---|---|
| 用户 | 事务方法、关键失败判断与代码 Review |
| Codex | 构建、安全骨架、Mapper、migration、中文业务注释、环境、Java 测试与实际验证 |

Learning Focus：Spring 事务代理怎样覆盖多次 SQL；数据库唯一约束如何裁决并发，以及应用准入与业务归属的区别。
不安排用户编写 Python 验证脚本。

## Review 与提交记录

- 已结合真实代码讨论事务代理与四次 Mapper 写入、数据库唯一约束与并发冲突回滚、默认 LANGUAGE 准入，以及真实 SQL 失败注入测试为何不能依赖测试外层事务。
- 已导读健康检查放行、其他入口拒绝、CSRF 与无 Session 的安全边界，以及新增文件、本地初始化脚本和 Maven 聚合 / 继承 / 依赖的职责。
- 用户指出提交阶段连接中断时不能盲目重试；讨论中进一步澄清：连接中断不等于事务失效或已回滚，提交结果可能为 UNKNOWN。此项是失败边界讨论，未进行提交确认丢失实验。
- 用户在上述讨论后作出 Commit Decision，并完成本地提交 `9b3ef1a`。Codex 自查不记作独立作者 Review；用户 Human Core 仍记为提示后实现，不据此提高模块掌握等级或标记整体 Identity Ownership PASS。
- 本次仅同步本文件与 PROJECT_STATUS；未改代码、未重跑构建或测试，也未创建新的提交。

## Verification

本节仅记录此新服务的证据，不沿用 C31 的历史通过作为 Identity 兼容结论。
初次离线构建因依赖尚未缓存失败，随后在不改变版本的情况下下载目标依赖。
第一次 HTTP 测试 8/9 通过，健康检查因过度限定整个 JSON 字符串而失败；
随后改为断言 UP 且无 components / details，允许框架公开的探针组名称。

PRIOR — 本 slice 前期骨架证据：HTTP 9/9、Mapper / schema / 账号边界 5/5 PASS；Human Core 占位时为
2 failures / 2 errors。第一版用户实现保留了成功路径的占位异常，回滚测试 2 项 PASS、成功相关测试 2 项 ERROR；
去除占位异常及不安全的异常打印后，用户事务方法 4/4 PASS。以上历史不代替本次集中验收。

FRESH 2026-10-21 — R1-A 提交候选的实际验收（现已提交为 `9b3ef1a`；本次记录同步未重跑）：

| 检查 | 结果与证明边界 |
|---|---|
| 工具与目标依赖 | Temurin 25.0.4+7、Maven 3.9.12；Boot 4.1.1、Security 7.1.1、Spring Context 7.0.9、MyBatis-Plus 3.5.17、MyBatis 3.5.19、PostgreSQL JDBC 42.7.13；Cloud 2025.1.3 仅导入 BOM |
| 集中测试与打包 | HTTP 9 + Mapper / schema / 账号边界 5 + 用户事务 4，共 18/18 PASS，0 failures / errors / skipped；同一次 Maven package 成功 |
| 事务与并发 | 正常创建完整账户、并发相同邮箱恰好一个成功、凭据 / 最后一步授权实际 SQL 失败时整笔回滚均通过；测试没有替 production bean 提供外层事务 |
| 真实应用重启 | 同一 JAR 的两个独立 JVM（PID 35365、36441）均 health 200 / UP；两次启动均确认 Flyway V1 已应用、无需重复迁移 |
| 持久化一致性 | 使用 identity_app 比较启动前及两次启动后的四表逐行脱敏摘要，完全一致；app_user / auth_identity / credential / grant 各 4 条，LANGUAGE 4、PERSONAL_NOTE 0 |
| 数据来源与环境 | 隔离 PostgreSQL 18.6；验收前已有 2 个合成账户，本轮测试新增 2 个完整合成账户；没有连接旧业务库或清库，未遗留失败注入 trigger |
| 候选一致性与清理 | 运行前后 source 摘要一致，两次启动 JAR 摘要一致；两个临时应用 JVM 已正常停止，隔离数据库保留 |

验收命令（先加载仓库外的私有环境文件）：

```sh
mvn -q package -o -pl services/identity -am -Dtest=IdentityBoundaryHttpTests,IdentityMapperIntegrationTests,LocalAccountPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

运行报告位于忽略的 `services/identity/target/surefire-reports/`；验收日志为 `target/r1-acceptance.log`、
`target/r1-start-1.log`、`target/r1-start-2.log`，source / JAR 摘要及三份逐行数据摘要也在 target 中。
数据摘要仅包含表名、内部记录标识和行摘要，不保存邮箱或 verifier 原文。实际密码及运行产物不提交。
重启验收写入端是 production 事务 bean，读取端是 Identity 专用账号的 SQL；当前没有公开账户读取 API。
此处验证的是应用 / JVM 重启，未重启数据库容器或模拟数据库崩溃。未设置数据库测试开关产生的 SKIPPED 不能算通过。

非阻塞 Review 项：当前 POM 额外显式声明 `spring-context:7.0.9`，与 Boot 4.1.1 BOM 的实际管理版本一致，
验收时未出现版本偏移；本次提交保留该声明，后续统一升级需复核版本覆盖，不作为本 slice 阻塞项。

NOT_RUN / 尚未证明：真实密码 policy / Argon2 验证、完整注册 HTTP、Session、跨服务认证 / 授权、
Dubbo 原版兼容、Cloud runtime、SAA、旧业务回归、前端、生产部署、数据库故障恢复、提交确认丢失及外层事务传播场景。

Acceptance: PASS（仅 R1-A 本地内部账户持久化）。[已实现事务 Flow](../flow/identity-account-persistence.md) 已按当前 source / tests 核对。
R1-A 已本地提交；当前 Gate 与 Next Action 以上方当前 slice 和下方 R1-B 合同为准，不提高 Ownership。

## R1-B — 受限密码散列与校验

### 已批准目标与边界

2026-10-22 用户接受本 slice、新增 Bouncy Castle 依赖，并选择实现并发控制方法；任务等级 A — Critical。
Identity 在内部提供 `hash(CharSequence)` 与 `matches(CharSequence, String)`，不开放 HTTP / RPC 注册或登录入口。
采用 concrete 组件组合；并发组件负责进程资源生命周期，异常类型区分容量不足与密码不匹配，不增加通用认证框架或共享实现模块。

- 参数沿用现有实现：`{argon2id-v1}`、Argon2id v19、memory 19456 KiB、iterations 2、parallelism 1、salt 16 bytes、hash 32 bytes。
- 每次散列使用随机 salt；原始密码不 trim、不改大小写，邮箱 / 密码 policy 由后续注册合同处理。
- 散列和校验共用一个进程级限额。配置 `app.security.password-hashing.max-concurrent`，环境变量 `IDENTITY_PASSWORD_HASH_MAX_CONCURRENT`，本地默认 1；非正数或非整数配置启动失败。
- 满额时立即拒绝，不等待、不排队；返回 false / null 或抛异常时也须归还本次已经取得的名额，被拒绝调用不得归还他人的名额。
- `matches` 对 null 密码、未知格式、非法 Base64 或非固定参数返回 false，且不执行 Argon2；容量不足传播专用异常，不伪装为密码不匹配。
- 组件不访问数据库、不启事务、不输出密码或 verifier；真实 verifier 与 R1-A 的组合只通过隔离集成测试验证，不新增账户 API 或 schema。

依赖只增加在 Identity POM：`org.bouncycastle:bcprov-jdk18on:1.86`。Java 25 / Boot 4.1.1 / Cloud 2025.1.3 基线不变；
旧 server 的 `1.85.2` 不修改，不恢复 C31 工程或补丁，不更换框架或引入 fork。
本次实际密码测试使用 Security Crypto 7.1.1 与 Bouncy Castle 1.86；不据此外推完整生产组合兼容。

Non-scope：完整注册、邮箱 / 密码 policy、blocklist、账户查找、密码升级、未知账号防枚举流程、Session / Redis、
HTTP / RPC 放行、Nacos / mTLS、跨服务授权、旧数据迁移、旧 server / 前端 / S11 修改及生产部署。
进程限额不等于集群限流或生产容量结论；本 slice 不添加自动重试或新的事务边界。

### 文件与调用链

新增 production Java 位于 `services/identity/src/main/java/com/dailylanguage/identity/credential/`：

| 文件 | 作者与职责 |
|---|---|
| LocalPasswordHasher.java | Codex；固定参数的真实散列 / 校验，以及执行前的 verifier 格式检查 |
| PasswordHashConcurrencyGate.java | Codex 准备构造器与注释；用户实现 `runWithAvailableSlot` |
| PasswordHashCapacityExceededException.java | Codex；表达暂时容量不足，不包含敏感输入 |

另修改 Identity POM 和 application.yml，新增 LocalPasswordHasherTests、PasswordHashConcurrencyGateTests、
IdentityPasswordHashingIntegrationTests；文档仅同步本合同与 PROJECT_STATUS。
当前 3 个 production Java 文件共 129 行，POM / 配置新增 11 行；用户核心方法完成后预计仍在 250 行预算内。

真实目标调用链：`hash / matches` → 共享 `runWithAvailableSlot` → Spring Security PasswordEncoder → Bouncy Castle Argon2。
数据库组合测试先生成 verifier，再调用已有 `createAccountWithLanguageAccess`；该方法及四条 SQL 不修改。

### Human Core 与分工

入口：[PasswordHashConcurrencyGate.runWithAvailableSlot](../../services/identity/src/main/java/com/dailylanguage/identity/credential/infrastructure/PasswordHashConcurrencyGate.java)。
用户只实现该方法的并发控制并移除占位异常；保留已有 null 检查、构造器和配置边界，不需要修改密码算法或其他生产文件。

验收条件：仅成功取得名额后执行 Supplier；满额立即抛 PasswordHashCapacityExceededException；结果原样返回，
异常原样传播；所有已取得名额在执行结束后归还一次，未取得名额不能归还；不记录敏感内容。
Codex 负责其余实现、Java 测试、隔离 PostgreSQL 验证与必要环境准备，不安排用户编写 Python 验证脚本。
Learning Focus：Semaphore 的容量与资源归还；原始密码、随机 salt、verifier 的边界。
求助先给定位 / 思路或伪代码，只有用户明确转交后才完整实现，不将测试替身记作用户核心方法的实现。

### Verification — 2026-10-22 骨架阶段

| 范围 | 实际结果与证明边界 |
|---|---|
| LocalPasswordHasherTests | 17/17 PASS；真实 Argon2 往返、随机 salt、密码原样处理、非法格式 / 参数拒绝和异常传播。容量组件使用直接执行操作的 mock，不证明真实并发控制 |
| IdentityBoundaryHttpTests | 9/9 PASS；保持已有健康检查 / 拒绝入口 / 无 Session 的 HTTP 骨架边界；该测试不启动真实数据库或完整 Identity 组件扫描 |
| PasswordHashConcurrencyGateTests | 12 项中 7 PASS、4 failures、1 error；配置 / null 防御通过，其余为 Human Core 占位导致的预期 RED，包含操作未执行造成的 latch 超时；不能算容量行为通过 |
| 集中命令 | 下述 Maven test 实际退出码 1，共 38 项、33 PASS、4 failures、1 error、0 skipped；不是整体验收 PASS |
| PostgreSQL / wider regression | 新真实 verifier 持久化集成测试已编译，未运行；R1-A 的 Mapper / 事务数据库回归本轮未运行，待用户核心实现后由 Codex 验证 |

```sh
mvn -q test -o -pl services/identity -am -Dtest=LocalPasswordHasherTests,IdentityBoundaryHttpTests,PasswordHashConcurrencyGateTests -Dsurefire.failIfNoSpecifiedTests=false
```

实际日志为忽略的 `services/identity/target/r1b-scaffold-verification.log`，报告在同一模块的 `target/surefire-reports/`，
骨架 source 摘要为 `target/r1b-scaffold-source.sha256`。旧 R1-A 的 18/18 是 2026-10-21 已提交候选的历史证据，不替代 R1-B 实际回归。
候选一致性风险：准备期间多次观察到 Identity POM 的新增依赖声明消失；用户确认非主动删除并授权补回。
期间未运行 Maven 的 55 秒观察未复现变化，后续仍观察到声明消失；写入来源 UNKNOWN，不能归因于 Maven 或 IDE。
声明已按批准合同补回，最终交接前核对骨架 source 摘要；下一次验收仍须先复核 POM 与源码，不将短时稳定当成根因已解决。
2026-10-22 骨架阶段结论（历史）：真实并发容量未通过，Human Core 待实现，停在 HUMAN_CORE_READY；真实 verifier 数据库往返、R1-B 独立 package / 完整服务启动、性能与生产容量、注册 / 登录 / Session 和跨服务验证均 NOT_RUN。

### Verification — 2026-10-24 Human Core 与依赖修复

- 用户在逐级提示与局部示例后完成 `runWithAvailableSlot`；Codex 基于实际 Diff Review，未发现并发控制阻塞问题。该实现记作提示后实现，不记作独立实现或自动提高 Ownership。最终核对时过期的实现 TODO 已移除。
- 修复前定向测试实际为 38 项、35 PASS、3 errors、0 skipped，退出码 1；三个错误均为缺少 `org.bouncycastle.crypto.params.Argon2Parameters$Builder`。
- 用户明确授权修复后，Codex 仅在 Identity POM 补回已批准的 `org.bouncycastle:bcprov-jdk18on:1.86`，未改用户方法。此前依赖声明缺失来源仍 UNKNOWN。
- FRESH：使用根目录 `mvnw.cmd` 执行同一组 LocalPasswordHasherTests、PasswordHashConcurrencyGateTests、IdentityBoundaryHttpTests；分别 17/17、12/12、9/9，共 38/38 PASS，0 failures / errors / skipped，退出码 0。
- 默认权限首次重跑在 Mockito attach 时遇 Windows 管道访问拒绝，已终止；上述 PASS 来自获准的正常权限环境重跑。报告位于 `services/identity/target/surefire-reports/`。
- 证明范围：真实 Argon2 往返、真实并发拒绝与名额归还、配置校验、既有 HTTP 边界。NOT_RUN：数据库组合与 R1-A 数据库回归、独立 package / 完整服务启动、生产容量、注册 / 登录 / Session 和跨服务验证。

该批次结束时尚待数据库验证；当前结论以下方剩余验收记录为准。

### Verification — 2026-10-24 剩余验收与整体自查

- Candidate：用户已提交 `3f0d36ffda52c9ea051d1bedfffd7192142b2137`，验收前工作区干净；本轮未修改 production / test source 或依赖。
- FRESH：新建一次性 PostgreSQL 18.6（官方 `postgres:18`，镜像 digest `sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6`），独立随机凭据、仅绑定 loopback、tmpfs 存储，初始化后核对隔离标识；应用使用无集群管理权限的 `identity_app`，没有连接旧业务数据库。
- 空 schema Flyway V1 成功；显式启用 `IDENTITY_DATABASE_TESTS=true`，执行以下命令，实际 package 退出码 0。

```powershell
.\mvnw.cmd -q -pl services/identity -am package '-Dtest=IdentityPasswordHashingIntegrationTests,IdentityMapperIntegrationTests,LocalAccountPersistenceIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- IdentityPasswordHashingIntegrationTests 1/1 PASS：真实 Argon2 verifier 经已有账户事务保存、读回，相同密码匹配、不同密码拒绝。
- IdentityMapperIntegrationTests 5/5、LocalAccountPersistenceIntegrationTests 4/4 PASS：schema / 账号边界、UUIDv7、四表写入、重复邮箱并发与凭据 / 默认授权失败回滚。合计 10/10，0 failures / errors / skipped。
- 独立 JVM 启动本次生成的 JAR，`/actuator/health` 返回 UP；本次没有重做两次 JVM 启动的逐行摘要比较，不替代 R1-A 的历史证据。
- 验证后停止本次 JVM，停止并自动移除本次数据库容器及 tmpfs 合成数据；未删除现有卷或业务数据。
- 运行摘要为忽略的 `services/identity/target/r1b-final-verification-summary.json`，Maven 日志为同目录 `r1b-database-package-verification.log`，测试报告在 `surefire-reports/`；没有持久保存本次临时密码。
- 整体自查基于 `2dda5de..3f0d36f` 的 Identity Diff、当前 source / tests 与上述证据：Scope MATCH，未发现 blocking finding；依赖已获批准，无新增 API、schema 或账户事务边界变化。Codex 对自己所写部分的检查属于自查，不记作独立作者 Review。
- Behavior Flow：[内部密码处理](../flow/identity-password-hashing.md) CURRENT；保持内部组件与组合测试边界，不把未来注册流程写为已实现。

Engineering Acceptance: PASS（仅获批 R1-B 范围）。Code State: COMMITTED `3f0d36f`。
Current Gate: COMPLETE / COMMITTED；本 slice 的 Ownership Check 结论及范围见下方讨论证据，不自动提高模块整体掌握等级。
R1-B 收口后的 Design / Scope 动作已由下方 R1-C 合同履行；后续实现范围与人工分工以当前合同为准。
NOT_RUN / 不证明：生产性能与容量、完整注册 / 登录 / Session、跨服务授权、生产部署与数据库故障恢复。

### User Review / Ownership — 2026-10-24

- 已结合真实代码导读 `hash → runWithAvailableSlot → Argon2 → 归还名额 → createAccountWithLanguageAccess`，明确当前持久化组合由 integration test 调用，尚无公开注册入口。
- 用户独立解释失败路径：“已取得的名额会 release；数据库不会留下半成品，因为 verifier 生成成功之后才会写数据库，没生成成功会直接 throw。”该回答正确追踪了资源归还、调用顺序和异常阻断；Codex 补充区分后续 SQL 写入失败时由账户事务回滚。
- Understanding: UNDERSTOOD（限定本次容量生命周期、散列前置与账户写入失败边界）。Human Core 仍记为提示后实现；不据此宣称独立实现、密码学全面掌握或 Identity 模块整体 Ownership PASS。
- 决策复盘：既有合同选择进程共享容量、满额立即拒绝、散列在事务前完成；代价是突发请求可能被拒绝，本进程限额不能替代集群限流。验证覆盖拒绝 / 归还、真实散列、数据库往返与事务回滚，不证明生产容量。未来根据真实负载评估容量、排队或服务边界时再单独决策，本次不改合同。

## R1-C — 注册密码策略与离线 blocklist

### 已批准目标与边界

2026-10-24 用户确认本方案、范围及 Human Core 分工；任务等级 A — Critical。
Identity 内部新增 `LocalPasswordPolicy.validate(password, normalizedEmail)`，只返回注册密码是否满足输入规则及拒绝原因。
沿用 [ADR-0002 Password Storage](../adr/0002-authentication-password-session-security.md#password-storage) 的既有政策，不改变密码规则、Argon2 参数、账户事务或 HTTP / Session 权限边界。

- 接受 12–64 个 printable ASCII 字符（U+0020–U+007E，含普通空格），不要求字符类型组合。
- 密码保留原样，不 trim、不改大小写、不做 Unicode normalization 或子串匹配。
- null 密码返回 INVALID_LENGTH；非法字符优先于长度判断。语法和长度合法后，缺失 normalizedEmail 抛 NullPointerException，说明调用方违反内部前置条件。
- 与 normalizedEmail 或其 local part 完全相同，或命中固定 blocklist 时返回 COMMON_OR_COMPROMISED；其余返回 ACCEPTED。
- normalizedEmail 由未来调用方规范化；本组件不推断、不规范化邮箱。policy 不调用 hasher、不访问数据库、不记录密码或 fingerprint。
- blocklist 只服务于注册等密码设置操作，不进入现有 `matches` 登录密码校验路径；查询仅计算本地 SHA-256 membership key，不能替代 Argon2 credential verifier。
- 资源缺失、结构异常、条目数或 checksum 不符时拒绝创建组件，不能静默跳过规则。资源加载后保留防御性副本，支持并发只读查询。

Non-scope：邮箱规范化、注册 Service、HTTP / RPC、登录、Session / Redis、Rate Limit、新 dependency / module、schema 或账户事务变更、旧 server 修改、上游 blocklist 更新、完整注册流程及生产容量结论。

### 文件、资源与职责

Production 新增仅三项：

| 文件 | 作者与职责 |
|---|---|
| `services/identity/src/main/java/com/dailylanguage/identity/credential/domain/LocalPasswordPolicy.java` | Codex 准备常量、构造器、enum 与字符 / 邮箱比较 helper；用户实现 validate |
| `services/identity/src/main/java/com/dailylanguage/identity/credential/domain/LocalPasswordBlocklist.java` | Codex；迁入已有固定资源校验与精确查询 |
| `services/identity/src/main/resources/security/local-password-blocklist-v1.bin` | Codex；原样复制旧模块的已固定资源 |

骨架两份 Java 共 191 行，Human Core 完成后共 204 行，仍在 250 行预算内；无新 POM / runtime 配置。
采用 concrete 组件组合：Policy 隔离注册输入规则，Blocklist 隔离固定资源加载、完整性校验与只读查询；不新建 Interface、通用框架或跨模块实现依赖。
新增两个同 package 测试类：LocalPasswordPolicyTests、LocalPasswordBlocklistTests；文档只同步当前合同与 PROJECT_STATUS。

资源基线：2,071 entries / 66,272 bytes，SHA-256 为 `dc4260507736ce8464d32b760097c38e105c66158fb8a9192e0a324d25929aa8`。
ADR / Phase Plan 中的 2,065 是 SecLists 过滤后的 baseline；现有生成器另加入六条项目相关值，得到当前 2,071 条，不构成规则冲突。
本轮没有重新下载或生成上游源，只核对迁入资源与现有资产的字节 checksum 一致；生成器不进入新模块 runtime 或 Maven build。

### Human Core 与验收

用户入口：[LocalPasswordPolicy.validate](../../services/identity/src/main/java/com/dailylanguage/identity/credential/domain/LocalPasswordPolicy.java)。
只实现该方法并移除 TODO / UnsupportedOperationException；构造器、enum、helper、blocklist 与测试由 Codex 准备，不要求修改其他 production 文件。

可使用的现有成员：`MINIMUM_LENGTH`、`MAXIMUM_LENGTH`、`containsOnlyPrintableAscii`、`matchesLoginIdentity` 和 `passwordBlocklist.contains`。
验收包括 null、11 / 12 / 64 / 65 长度边界、完整 printable ASCII、空格保留、非法字符优先级、邮箱 / local part exact matching、固定弱密码拒绝以及敏感值不进入输出。
非法语法 / 长度应在 blocklist 查询和 normalizedEmail 前置检查之前被拒绝；合法密码缺失 normalizedEmail 的错误消息固定为 `normalizedEmail must not be null`。
测试直接调用真实 validate；不将未实现异常改成成功预期、不跳过 Human Core 测试，不由 Codex 代写该方法。

Learning Focus：校验顺序如何决定失败结果；邮箱规范化不能反向改写密码。
User Review 入口：validate 的分支顺序、原始密码比较与 blocklist 资源失败边界。求助沿用逐级提示，明确转交后才完整实现。
完整行为、最终 Review / Ownership 和必要 Flow 同步留在 Human Core 完成后，不将尚未实现的 policy 写入已实现注册 Flow。

### Verification — 2026-10-24 骨架阶段

FRESH 命令：

```powershell
.\mvnw.cmd -q -pl services/identity -am test '-Dtest=LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

| 范围 | 实际结果与证明边界 |
|---|---|
| LocalPasswordBlocklistTests | 12/12 PASS；固定资源、精确查询、结构 / count / checksum / 缺失资源拒绝、防御性复制与并发只读；真实 Spring Policy / Blocklist bean 创建成功，但未执行 validate |
| LocalPasswordPolicyTests | 8 项全部 RED：1 failure、7 errors，均来自真实 Human Core 占位 UnsupportedOperationException，包括期望 NullPointerException 的差异；不能称 policy 验收通过 |
| 既有回归 | LocalPasswordHasherTests 17/17、PasswordHashConcurrencyGateTests 12/12、IdentityBoundaryHttpTests 9/9，共 38/38 PASS |
| 集中结果 | 58 项、50 PASS、1 failure、7 errors、0 skipped；实际退出码 1，符合 HUMAN_CORE_READY 的占位状态，不是整体验收 PASS |

日志为忽略的 `services/identity/target/r1c-scaffold-verification.log`，XML 报告位于同模块 `target/surefire-reports/`。
资源 checksum 与旧资产一致，旧 server、POM、application.yml、hasher 和账户事务均未修改。
NOT_RUN：上游源重新生成、完整 JAR / PostgreSQL / browser / Session / 跨服务验证；R1-B 历史数据库证据不当作本 slice fresh result。
完成 Human Core 后先运行 policy / blocklist tests，再跑受影响回归与资源 / 组件启动检查；根据真实变化扩大验证，不运行无关全量测试。

以上为骨架阶段的预期 RED；当前结果以下方 Human Core Review 为准。

### Human Core Review / Verification — 2026-10-24

- 用户先完成部分实现，再根据局部 Review 提示调整 null / 字符 / 长度顺序、补齐长度上限、邮箱前置条件和弱密码判断；Human Core 记为提示后实现，Codex 未修改用户方法，不记作独立实现。
- Code Review: PASS，无 blocking finding；Scope MATCH。真实 validate 保持原始密码，不规范化邮箱或密码；返回原因与检查顺序符合合同。normalizedEmail 缺失仅在密码语法 / 长度合法后抛前置条件异常。
- blocklist 迁入代码仍为 Codex 自查，不记作独立作者 Review；资源 pinned 校验、immutable lookup 与测试覆盖符合当前 Scope。无新增 dependency、HTTP / Session 放行、schema、账户事务或旧 server 修改。
- FRESH：以下测试与 package 一次运行退出码 0；LocalPasswordPolicyTests 8/8、LocalPasswordBlocklistTests 12/12、LocalPasswordHasherTests 17/17、PasswordHashConcurrencyGateTests 12/12、IdentityBoundaryHttpTests 9/9，共 58/58 PASS，0 failures / errors / skipped。

```powershell
.\mvnw.cmd -q -pl services/identity -am package '-Dtest=LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 校验生成 JAR 的 `BOOT-INF/classes/security/local-password-blocklist-v1.bin`：66,272 bytes，SHA-256 与批准的固定值一致；证明打包未遗漏或改变资源。真实 Spring Policy / Blocklist bean 创建由上述 12 项 blocklist 测试覆盖。
- 日志为忽略的 `services/identity/target/r1c-human-core-review.log`，XML 报告位于 `target/surefire-reports/`。
- Critical Flow：validate 接收原始密码 → null / 字符 / 长度拒绝 → normalizedEmail 前置条件 → exact identity / blocklist 拒绝 → ACCEPTED；只查询固定资源，没有账户或长期状态 mutation。
- Human Review Focus：预期输入拒绝使用 ValidationResult，内部前置条件失效使用异常；字符错误优先级；密码原值与完整字符串比较；blocklist SHA-256 仅为查询 key，credential verifier 仍由 Argon2 负责。
- Behavior Flow: NOT_REQUIRED；当前是同一 credential 包内可直接追踪的输入校验与固定表查询，未形成注册跨模块调用链，现有 hash / matches Flow 未改变；后续接注册流程时同步真实 Flow。
- NOT_RUN：完整服务 JVM / PostgreSQL、浏览器、Session、跨服务及上游 blocklist 重新生成；本 slice 未修改这些路径，不以历史运行代替 fresh 证据。

Engineering Acceptance: PASS（仅 R1-C 内部密码策略与 blocklist，历史证据）。R1-C 已提交 `1738792`；Human Review 证据见下方记录。
2026-10-25 提交状态同步：R1-C COMPLETE / COMMITTED；下一 slice 仅进入 Design / Scope，不自动 commit、push 或实现。

### User Review / Ownership — R1-C，2026-10-24

- 用户实际编写 validate，并在局部 Review 后修正 null / 字符 / 长度顺序、最大长度、邮箱前置检查及身份 / blocklist 拒绝路径；该 Human Core 保持提示后实现记录。
- 用户质疑密码输入拒绝与 normalizedEmail 缺失的比较，指出空邮箱可能来自未输入或上游处理问题，要求讨论基于真实调用层级而不是预设概念。当前内部前置条件合同保持不变；未来注册入口仍需明确邮箱输入校验及错误传播，不能把尚未接通的调用链视为已实现。
- 用户针对显式 if / return 的繁琐提出 Review 意见，经讨论决定当前保留实现，未来触及相关代码时按实际需要评估简化；本次没有批准规则变更或额外重构。
- Understanding: UNDERSTOOD（限定本 slice 的校验顺序、输入规则与调用边界判断）；以上实现、修正和真实 Review 讨论作为 Ownership evidence，不要求补答概念题，不提升整个 Identity 或密码学模块的掌握等级。
- 收口核对：validate 当前 blob `22ecf134314165f77bd2692ad533c6ffa1668c9c` 与已 Review 的实现一致；沿用同日已记录的 58/58、package 与资源完整性验证，本次仅同步 Review 结论，未重跑测试。

Human Review: COMPLETE。R1-C Gate: COMPLETE / COMMITTED `1738792`（2026-10-25 按 Git 同步；不重开 Review）。

## R1-D — Identity 内部 LOCAL_EMAIL 注册闭环

### 已批准目标与边界

2026-10-25 用户确认完整内部闭环方案，并明确承担 Human Core；等级 A — Critical。
在 R1-A 四表事务、R1-B 受限散列和 R1-C 密码策略之上新增注册调用方，不重写已完成的 validate。
仅供内部 Java 调用与工程验证，当前没有公开 HTTP / RPC 注册入口。

目标链：LocalRegistrationService.register → LocalEmailNormalizer.normalize → 既有 LocalPasswordPolicy.validate
→ 既有 LocalPasswordHasher.hash / capacity gate → 既有 LocalAccountPersistence 事务代理 → 返回 UUID。
成功时 app_user、auth_identity、local_password_credential、默认 LANGUAGE app_access_grant 一起提交。

- 邮箱沿用既有 ASCII / 254 字符及 local part / domain label 规则：strip 首尾空白、先拒绝非 ASCII，再用 Locale.ROOT 小写化并检查语法；不合并 plus / dot，不查 DNS 或验证邮箱所有权。
- 原始密码不改写，policy 拒绝后不能散列或写库；正常调用没有外层事务，Service 不标注 @Transactional，昂贵散列在进入账户事务前完成。
- 不增加重复邮箱预查询；数据库唯一约束裁决并发。不得接收外部 verifier、userId、appCode 或管理员资格。
- 仅复用 concrete 组件；新 Service 承担输入到计算、事务及安全失败之间的编排职责，不新增 Interface / Factory / module / dependency。
- 不修改原账户事务、policy、hasher、容量参数、schema、安全配置或旧 server；不迁入旧三表 persistence。

Non-scope：公开注册 / 登录、Session / Redis、Rate Limit、Gateway、Dubbo / Nacos / mTLS、业务消费者、前端、
邮箱验证 / 恢复、自动重试 / 幂等、生产容量、提交确认丢失实验、依赖升级与额外重构。

### 文件、预算与失败合同

新增 production 文件均位于 services/identity/src/main/java/com/dailylanguage/identity/account/：
LocalEmailNormalizer.java、LocalRegistrationService.java、LocalRegistrationException.java。
旧参考实现共 234 行，预计最终 230–250 行 / 3 文件；明显超出时回到 Scope Decision，不牺牲可读性硬凑预算。
新增同 package 测试 LocalEmailNormalizerTests、LocalRegistrationServiceTests、LocalRegistrationIntegrationTests。
文档同步本合同与 PROJECT_STATUS；真实链验证完成后再创建注册 Behavior Flow 并登记索引，骨架不记作已实现 Flow。

| 失败 | 内部 failure reason / 处理 |
|---|---|
| 邮箱 null / 非法 | INVALID_EMAIL；不进入 policy、散列或持久化 |
| 密码长度 / 字符 / 弱密码 | INVALID_PASSWORD_LENGTH / INVALID_PASSWORD_CHARACTER / COMMON_OR_COMPROMISED_PASSWORD；不散列、不写库 |
| policy 意外 RuntimeException | REGISTRATION_FAILED；只记 PASSWORD_POLICY stage 和 exception type |
| hash 容量不足 | REGISTRATION_FAILED；立即拒绝，不排队、不写库、不记 ERROR |
| 其他 hash RuntimeException | REGISTRATION_FAILED；只记 PASSWORD_HASH stage 和 exception type，名额由既有 gate 归还 |
| persistence DuplicateKeyException | IDENTITY_UNAVAILABLE；经过独立事务代理传播后映射，不记录敏感异常 |
| 其他 persistence RuntimeException | REGISTRATION_FAILED；只记 PERSISTENCE stage 和 exception type，不返回成功、不自动重试 |

异常使用固定消息、不附带原始 cause chain；不记录邮箱、密码、verifier、SQL 参数或数据库异常正文。
IDENTITY_UNAVAILABLE 是安全映射，不把任意唯一冲突都宣称为已精确识别的邮箱冲突；未来唯一键扩展时重新评估。
已确认 SQL 写入失败可验证整笔回滚；提交确认丢失可能是 UNKNOWN，generic failure 不承诺账户不存在。
Architecture / Data / external API Impact：无新增架构机制、schema、对外合同或权限放行；Security Impact：A 类内部编排。

### Human Core、分工与验收

- 用户：LocalRegistrationService.register(String submittedEmail, String submittedPassword) 的主流程与持久化失败分支，预计 20–30 行；不要求修改已有 validate。
- Codex：normalizer、constructor、normalizeEmail / validatePassword / hashPassword / logUnexpectedFailure helper、异常模型、Unit / Integration Tests 与验证。
- 骨架只提供签名、可调用的 helper、依赖字段和明确抛出的未实现占位；Codex 不给出完整 register 替换答案。
- Learning Focus：计算资源与事务的边界；失败返回与数据库提交结果的区别。
- User Review 入口：register 的顺序 / 拒绝短路 / catch 范围；helper 的安全映射；真实事务与并发测试。

Acceptance Criteria：按邮箱 → policy → hash → persistence 顺序执行，传递规范化邮箱与原始密码；
只成功返回真实 userId；唯一冲突和意外持久化异常安全映射；不扩大事务、不输出敏感值、不重试。
helper 不写数据库，账户事务仍由独立 Spring bean 提供。用户实现后基于真实 Diff 与测试 Review，不安排固定概念问答。

### Verification Plan

1. Normalizer unit：首尾空白、小写化、plus / dot 保留、ASCII / 语法和长度边界、Unicode compatibility 拒绝。
2. Service unit：顺序与原值、各拒绝分支短路、意外异常 / 唯一冲突 / 容量映射，以及固定异常与日志脱敏。
3. 新注册 PostgreSQL integration 只验证真实组件接通、四表 / verifier 与 hash 无活动事务；输入拒绝和异常映射由 Service unit 覆盖。
4. 并发唯一冲突及凭据 / 授权失败回滚复用既有 LocalAccountPersistenceIntegrationTests，不在新注册层复制协调线程和故障注入。
5. 用户完成后执行既有 policy / blocklist / hasher / capacity / HTTP 回归、账户数据库回归与 package；输出真实 fresh / prior / skipped / NOT_RUN。

骨架阶段不启动数据库执行占位流程；先编译测试并运行 normalizer、Service unit 和最相关既有回归。
Service 行为测试应因 Human Core 未完成保持 RED；数据库链、完整 Slice 验收及 Ownership 仍待用户实现。

### Verification — 2026-10-25 骨架阶段

- 首次 testCompile 发现容量异常构造器仅包内可见；只调整新测试为 Mockito 按异常类型抛出，不扩大 production API。随后全部测试源码编译成功，包含 LocalRegistrationIntegrationTests。
- 默认权限运行停留在 Mockito 自附加阶段，尚无行为测试结果；已结束受阻命令，随后在获准的正常 Windows 权限环境重跑同一定向测试，未修改 POM / agent 配置。
- FRESH：LocalEmailNormalizerTests 25/25 PASS；既有 policy 8/8、blocklist 12/12、hasher 17/17、capacity 12/12、HTTP 9/9，共 58/58 PASS。
- FRESH：LocalRegistrationServiceTests 14 项均因 register 未实现而 RED（13 failures、1 error）；XML 中 14 项失败均包含 Human Core 占位异常，未发现其他失败原因。总计 97 项，83 PASS、0 skipped，实际退出码 1，不是整体验收通过。

```powershell
.\mvnw.cmd -q -o -pl services/identity -am test '-Dtest=LocalEmailNormalizerTests,LocalRegistrationServiceTests,LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 日志：忽略的 services/identity/target/r1d-scaffold-tests-authorized.log；XML：target/surefire-reports/。骨架三个 production 文件共 206 行，最终实现仍需核对 250 行认知预算。
- 自查边界：没有修改现有组件、事务、schema、安全配置、POM 或旧 server；register 保留明确失败占位，未替用户实现。helper 的端到端分支需用户完成主流程后由 Service tests 验证，不因 helper 存在就宣称链路可用。
- NOT_RUN：新增 5 项真实数据库用例及既有数据库回归、package / JAR 资源校验、完整服务启动、提交确认丢失、浏览器 / Session / 跨服务。数据库测试仅编译，不以环境条件跳过记作通过。

### 测试去重 — 2026-10-25

用户要求只审视、删除 Identity 测试冗余；未修改 production、Human Core 或依赖，R1-C Review 不重开。
检查 src/test 下全部 12 个 Java 文件，修改 8 个，测试总行数 1,302 → 1,058，净减少 244 行；没有新增文件、工具或测试依赖。

- 删除新注册 integration 中重复的输入拒绝、并发唯一键与 trigger 回滚用例；这些分别由 Service unit 与既有账户事务测试承担。
- 删除 Mapper 的重复四表成功 / 唯一约束用例，以及 hasher 的 mock 容量拒绝；真实账户事务与 capacity gate 测试保留。Mapper 原有 identity UUIDv7 断言移入剩余存储约束用例，没有删除该独有检查。
- 精简同类非法邮箱排列、全 printable ASCII 枚举、重复 blocklist 结构校验 / 并行只读压力及纯组件无日志断言；保留真实资源完整性、密码边界和安全参数校验。
- 既有 IdentityPasswordHashingIntegrationTests 保留：register 尚未实现，新的 RED 注册链不能替代已经可运行的 hasher / 持久化组合证据。
- R1-D 的三个新增测试类仍分别验证纯邮箱规则、Service 安全失败 / 短路合同，以及真实 Spring / 数据库组合，不能靠新增文件数与 production 类一一对应来判断必要性。当前 R1-D 测试共 278 行 / 骨架 production 206 行，约 1.35 倍。
- FRESH：定向运行 normalizer 8、policy 7、blocklist 8、hasher 14、capacity 8，共 45/45 PASS；Service 11 项占位 RED（10 failures、1 error），合计 56 项、0 skipped，退出码 1。此前 97 项结果是精简前的 PRIOR，不代表当前测试数量。
- 新增注册 integration 当前只剩 1 项，全部测试源码已编译；新 / 既有数据库运行、HTTP 重跑、package 均 NOT_RUN。日志为忽略的 target/identity-tests-pruned.log；通过前后 SHA-256 核对 production 文件 0 改动，diff whitespace 检查通过。

### Human Core 增量 Review — 2026-10-25

- 用户在逐步提示后完成注册成功路径和唯一冲突映射；第二个 catch 当前捕获 LocalRegistrationException，不能捕获测试注入的 DataAccessResourceFailureException，且遗漏 PERSISTENCE 安全日志。Codex 只读 Review，未修改用户实现。
- FRESH：仅运行 LocalRegistrationServiceTests，11 项中 10 PASS、1 failure、0 errors / skipped，退出码 1。失败项 mapsUnexpectedPersistenceFailureWithoutLeakingCauseOrRetrying 观察到原始数据库异常直接传播，尚未进入业务异常转换；安全日志要求也需补齐。
- 日志：忽略的 target/r1d-register-review.log；其余 45 项组件测试及数据库 / HTTP / package 本批 NOT_RUN。此前占位 RED 与测试去重结果均保留为 PRIOR，不能当作当前实现结果。
- 当前证据只说明用户提示后实现和这一处具体理解 / 实现缺口，不判定完整 Slice Ownership 或模块掌握等级。

### Human Core 修正与定向验证 — 2026-10-25

- 用户根据提示 / 局部 catch 示例，将第二个 catch 改为 RuntimeException，并补上 logUnexpectedFailure("PERSISTENCE", exception) 后抛固定 REGISTRATION_FAILED。此前具体 Finding 已解除；没有将原始数据库 cause 附加到业务异常。
- Codex 未修改用户 register；Human Core 如实记录为提示 / 局部示例后实现，不记作独立实现，也不自动推高模块掌握等级。
- FRESH：仅重跑既有 LocalRegistrationServiceTests，11/11 PASS、0 failures / errors / skipped，退出码 0。原有 10 PASS / 1 failure 是本日修正前的 PRIOR。
- 代码增量自查：保持邮箱 → policy → hash → 独立账户事务顺序；具体唯一冲突先处理，其他持久化 RuntimeException 安全转换，不重试。register source blob：b498eb26134012e91fb715c1443f7fd43eace94a。
- 日志：忽略的 target/r1d-register-fixed-review.log。新 / 既有数据库组合、其他组件 / HTTP 回归、package 与 Behavior Flow 本批尚未完成；11 项 mock-based Service tests 不证明真实提交 / 回滚，也不代替用户 Critical Review。

### 整体 Code Review / Verification — 2026-10-25

- Scope MATCH：R1-D 三个新增 production 文件最终共 215 行，新增测试 278 行；另含用户明确授权的 Identity 测试去重。没有新 dependency、schema、公开 API、安全配置、旧 server 或事务机制修改。采用 concrete composition，Extensibility Fit: RIGHT_SIZED。
- 已核对真实 Git Diff、untracked 新增源文件、直接依赖、测试与合同；源码自查未发现新的正确性 / 安全缺陷。Codex 对自有实现的检查仅为自查，不冒充独立作者 Review。
- FRESH：以下一次命令退出码 0；normalizer 8、Service 11、policy 7、blocklist 8、hasher 14、capacity 8、HTTP 9，共 65/65 PASS、0 failures / errors / skipped；package 成功。日志：忽略的 target/r1d-full-review-package.log。用户 register blob 仍为 b498eb26134012e91fb715c1443f7fd43eace94a，Review 未修改实现或测试。

```powershell
.\mvnw.cmd -q -o -pl services/identity -am package '-Dtest=LocalEmailNormalizerTests,LocalRegistrationServiceTests,LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- Verification: PARTIAL。阻塞缺口：LocalRegistrationIntegrationTests 的真实 Spring / Argon2 / 四表组合从未执行；同候选下现有 Mapper 3、账户事务 4、密码数据库组合 1、新注册组合 1，共 9 项数据库验收 NOT_RUN。Service 使用 mock，HTTP context 排除数据源，不能替代这些证据；不能去掉隔离标识或条件注解来制造通过。
- Behavior Flow: MISSING。既有账户与密码 Flow 未覆盖新增 normalize / policy / registration failure 链；应在真实链验证后补齐并登记。Review 不自动创建 Flow 或修复实现。
- Critical Flow：原始输入 → normalize / policy → hash 及名额归还 → 独立账户事务代理 → 四表写入 / 提交 → UUID；明确 SQL 失败与唯一冲突回滚后映射，提交确认丢失仍可能 UNKNOWN，不自动重试。
- User Review Focus：register 的状态写入边界、helper 的安全异常转换与真实事务测试；Human Core 保留提示 / 局部示例后实现，不自动标记完整 Ownership，不追加固定概念题。

### 工程验收与 Flow 修正 — 2026-10-25

- 用户授权修正上一轮验收 / Flow 缺口。沿用 migrate/identity / e6b8e22；未修改 production、测试、依赖或用户 Human Core，register 前后 blob 均为 b498eb26134012e91fb715c1443f7fd43eace94a。
- FRESH：新建一次性 PostgreSQL 18.6，使用已缓存官方镜像 postgres:18（digest sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6），loopback 随机端口、tmpfs、独立随机凭据，复用 deploy/local/identity/init-databases.sql；没有运行中的既有容器，未读取或修改旧业务库 / 既有卷。
- 应用使用非集群管理员 identity_app；执行前核对 DAILYLANGUAGE_R1_ISOLATED_VERIFICATION_ONLY 标识。显式启用 IDENTITY_DATABASE_TESTS=true、容量保持 1，从空 schema 应用 Flyway V1 成功。
- FRESH：LocalRegistrationIntegrationTests 1/1、LocalAccountPersistenceIntegrationTests 4/4、IdentityMapperIntegrationTests 3/3、IdentityPasswordHashingIntegrationTests 1/1，共 9/9 PASS、0 failures / errors / skipped，package 退出码 0。

```powershell
.\mvnw.cmd -q -o -pl services/identity -am package '-Dtest=LocalRegistrationIntegrationTests,LocalAccountPersistenceIntegrationTests,IdentityMapperIntegrationTests,IdentityPasswordHashingIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 证明范围：真实 Spring / policy / 受限 Argon2 / 四表事务接通，散列时无活动事务、密码空格保留；并发重复邮箱恰好一成功且无孤立账户，凭据 / 默认授权 SQL 失败回滚，专用账号 / schema 边界和真实 verifier 往返。新注册组合只验证成功链，失败映射仍由 Service unit 与既有真实事务回归分层证明，未重复新增故障测试。
- 验证结束四表各 4 行合成样本，Flyway 成功记录 1 条、遗留测试 trigger 0。一次性容器 dailylanguage-r1d-review-f78e29ffb4 已停止并自动移除，tmpfs 数据清理；进程环境恢复，未输出或持久保存随机密码。
- 脱敏证据：忽略的 target/r1d-final-verification-summary.json、r1d-database-package-verification.log、r1d-verification-cleanup.log；上批 65/65 与 package 是同日 PRIOR，不与这次 9 项混写为单次运行。
- 新增并登记 [内部注册 Flow](../flow/identity-local-registration.md)，同时只更新已受影响的账户 / 密码 Flow 调用方说明；Flow 依据当前 source 与上述验证，不将未来公开注册、Session 或跨服务行为记为已实现。
- delta-only 自查：两项缺口已解除，Scope MATCH，Code Review / Engineering Acceptance: PASS（仅 R1-D 内部闭环）；Behavior Flow: CURRENT。无新增生产 / 测试文件或代码行数；本次只新增 Flow 文档和同步相关记录。Codex 自查不冒充独立作者 Review。
- NOT_RUN：独立 JAR JVM 健康重启 / 双启动摘要、数据库崩溃恢复、提交确认丢失、生产容量、公开认证 / Session / 浏览器 / 跨服务；这些不属于本次通过结论。

### User Review / Ownership 收口 — 2026-10-25

- 用户实际完成 register 的输入处理顺序、参数传递、账户调用和持久化失败分支，并在真实 10 PASS / 1 failure 的增量 Review 后修正异常捕获类型与安全日志；包含明确提示及局部 catch 示例，保持“提示 / 局部示例后实现”记录，不记作独立实现或无提示 Debug。
- 用户看过代码后，提出邮箱 / 密码输入错误必须可区分、以支持未来前端通知的具体 Review 要求。核对 normalizeEmail / validatePassword 的 failureReason 及数据库 try 范围后，确认现有输入错误不会被持久化通用失败覆盖；邮箱目前仍只有 INVALID_EMAIL，不虚构已具备逐条邮箱规则 reason 或前端响应接口。
- 用户在该问题澄清后要求继续。结合实际编写 / 修正、源码调用追踪及这次具体错误分类 Review，本 Slice Human Review 收口；不以“看过代码”或测试通过单独证明 Ownership，不追加固定概念问答。
- Understanding: UNDERSTOOD，限定当前 register 的调用顺序、状态写入 / 成功返回，以及输入拒绝与持久化异常的传播边界；不据此认定整个 Identity、Argon2、Spring 事务或前端契约已掌握。OWNERSHIP_MATRIX 等级保持原样。
- 工程复盘：复用既有规则、受限散列与独立账户事务，通过 concrete Service 形成内部闭环；保留具体输入拒绝，同时对底层故障作安全转换。代价是尚无公开入口 / 前端文案映射；未来公开注册与更细错误合同仍需单独 Scope。提交确认丢失仍可能 UNKNOWN，不引入自动重试。
- 本次仅核对 Git 与 register blob b498eb26134012e91fb715c1443f7fd43eace94a，和已验收候选一致；沿用同日 65/65 与数据库 9/9 两批 PASS、package 与 Flow 证据，未新增测试或重跑验证，未修改 production。

Human Review: COMPLETE（仅 R1-D）。R1-D Gate: COMPLETE / COMMITTED `c1be36d`。
2026-10-25 提交状态同步：用户已提交 c1be36de052b47ba2a08fc67e720548718105432；register blob 仍为 b498eb26134012e91fb715c1443f7fd43eace94a，未重开 Review 或重跑测试。
Next Action：下一 Identity Slice 的 Design / Scope 建议见当前对话，等待用户决定范围与分工；本次不自动实现、commit 或 push。

## R1-E — 内部 LOCAL_EMAIL 凭据认证

### 已批准目标、方案与边界

2026-10-25 用户回复“同意”，采纳推荐的完整内部认证闭环和用户承担 Human Core 分工；等级 A — Critical。
当前 R1-A–D 已提供账户事务、受限 hasher、注册 policy 和内部注册，但 Identity 尚无凭据认证入口。
沿用 Spring AuthenticationProvider 生命周期，直接组合现有 Mapper 与 hasher；相比只增加凭据读取，完整闭环可验证身份产生条件与失败边界，代价是新增认证安全逻辑与 Review。
不增加 Repository 包装层、Service Interface、公共认证 module 或新 dependency。

目标链：authenticate → 邮箱规范化 → Mapper 只读 LOCAL_EMAIL credential → 真实 / dummy verifier 受限校验
→ 安全拒绝或只含 userId 的 AuthenticatedAccount → finally 清除请求 token 的 credentials。

- 有效邮箱复用 LocalEmailNormalizer；未知账户、非法邮箱与错误密码统一 BadCredentialsException / Invalid credentials。
- 未知账户 / 非法邮箱仍执行合法 dummy verifier 校验，即使匹配也不能返回身份；dummy 在组件创建时经现有 hasher.hash 生成一次，初始化失败拒绝创建组件，不落库。
- 原始有效密码不 trim、不改写，登录不调用注册 policy / blocklist；非 CharSequence credentials 按空字符串验证。
- 规范化输入拒绝与 Mapper 查询故障分开捕获，Mapper 的 IllegalArgumentException 也归 authentication unavailable。
- Mapper / hasher 故障转换为 AuthenticationServiceException / Local authentication unavailable；日志仅 stage 与 exception type，不附带原始 cause。密码校验容量不足直接 unavailable，不记 ERROR；dummy 初始化失败记录安全阶段并拒绝创建组件。
- 成功 token 的 principal 仅 userId、credentials 为 null、authorities 为空；finally 在成功 / 失败时清除原始请求 token 的密码引用，不声称擦除 JVM 中的 String。
- 只读取 auth_identity 与 local_password_credential；不读写 app_access_grant。当前 app_user 无停用状态，本 Slice 不引入账号启停；凭据认证成功不表示应用准入。

Architecture / Security Impact：增加内部认证边界与 identity principal，需 A 类 Review；沿用既有框架、package、密码参数与共享容量。
无 schema、对外 API、事务、cache、应用授权机制或 dependency 改变。
Non-scope：HTTP 登录 / 注册、Session / Redis、Rate Limit、Gateway、Dubbo / Nacos / mTLS、业务消费者、前端、账号启停、恢复授权、schema / dependency 升级及旧 server 修改。

### 文件与代码预算

Production 共 5 文件：account 下新增 LocalPasswordAuthenticationProvider.java、LocalPasswordCredential.java、AuthenticatedAccount.java；
扩展 LocalAccountMapper.java 与 resources/mapper/LocalAccountMapper.xml，只增加凭据 SELECT 和构造器结果映射。
credential 仅内部可见，toString 脱敏；principal 不持有邮箱、密码或 verifier。
原预计 production 180–230 行；骨架约 160 行，工程验收候选约 173 行。用户在 Review 时删除 3 行已完成的骨架说明并留空行，当前新增 / 实质修改约 171 行（3 个新增文件 155 行，Mapper Java / XML 约 16 行），仍在预算内。

测试只新增 LocalPasswordAuthenticationProviderTests.java（177 行），扩展既有 IdentityPasswordHashingIntegrationTests.java（35 行），合计约 212 行。
新文件验证此前没有的 Provider 认证行为与安全失败合同；复用现有 Mockito / OutputCapture / 数据库 fixture，不新增测试工具、基类、依赖或测试工厂。
注册 → Mapper 读取 → 真实认证 → 已撤销应用准入不恢复的组合证据放在现有数据库测试中，不复制账户事务 / 并发测试。
文档只同步本合同与 PROJECT_STATUS；核心段和真实链完成后再按 Flow 规则补认证行为，不将骨架写成已实现 Flow。

### Human Core、分工与验收

- 用户：LocalPasswordAuthenticationProvider.authenticate 内 try 块中，lookupCredential 之后的核心判定，预计 15–25 行；替换明确的 UnsupportedOperationException 占位。
- 已提供：storedCredential、submittedPassword、unknownAccountPasswordVerifier、verifySubmittedPassword、INVALID_CREDENTIALS；finally 清除密码由 Codex 完成。
- 核心行为：选择真实 / dummy verifier，调用受限校验；账户存在且匹配才允许返回身份，其余统一凭据拒绝。
- 成功结果使用 Spring UsernamePasswordAuthenticationToken，principal 为 AuthenticatedAccount(userId)，credentials 为 null，authorities 为空。
- Codex：Mapper / XML、两个数据类型、dummy 初始化、输入 / 故障转换 helper、框架衔接、finally 清理、日志、Unit / Integration Tests 与后续获准验证。
- Learning Focus：凭据认证与应用授权的边界；dummy 校验结果与账户存在性是两个独立判断。
- User Review 入口：authenticate 核心分支、lookupCredential 的两个 catch 范围、verifySubmittedPassword 的故障分类，以及真实数据库组合。

Acceptance Criteria：真实存在账户且密码匹配才产生身份；未知 / 非法邮箱执行 dummy 但不能认证；有效密码原样传递；基础设施故障不伪装为错误凭据；成功 / 失败均清除请求 token 密码；异常与日志不泄露敏感材料；认证不恢复应用准入。
骨架阶段 Human Core 留给用户，Codex 未提供完整替换答案；用户后续实现与修正见下方增量 Review 记录，不据此自动标记整体 Ownership 已完成。

### Verification — 2026-10-25 骨架阶段

- FRESH：根目录 mvnw.cmd 离线 test-compile 退出码 0，全部测试源码（含数据库组合）编译成功。
- FRESH：既有 normalizer 8、Service 11、policy 7、blocklist 8、hasher 14、capacity 8、HTTP 9，共 65/65 PASS；新 Provider 11 项中 5 PASS、5 failures、1 error，六项 RED 均包含核心判定占位异常。总计 76 项、70 PASS、0 skipped，实际退出码 1，不是 Slice 验收 PASS。
- 默认权限首轮停在 Mockito 自附加阶段且无行为结果，已终止；上述结果来自获准的正常 Windows 权限重跑，未修改 POM / agent 配置。diff whitespace 检查 PASS。
- PostgreSQL / Flyway / 真实 Mapper 映射 / 完整组件初始化与注册认证组合、package / 独立 JAR、公开登录 / Session、旧 server / browser / 跨服务本轮 NOT_RUN。
- R1-D 65/65 与数据库 9/9 为 PRIOR，不代替 R1-E 证据。

```powershell
.\mvnw.cmd -q -o -pl services/identity -am test '-Dtest=LocalPasswordAuthenticationProviderTests,LocalEmailNormalizerTests,LocalRegistrationServiceTests,LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

报告入口：services/identity/target/surefire-reports/；本轮测试输出为 services/identity/target/r1e-scaffold-tests.log。
骨架自查仅确认 Scope、只读 SQL、catch 范围、脱敏和密码清理结构；dummy / 密码校验 helper 的完整调用、实际身份成功条件与真实数据库结果待核心段完成后验证，不代替用户 Review。

Current Gate: HUMAN_CORE_READY。Next Action：用户实现 authenticate 核心判定后，进行增量 Review 与受影响验证。

### Human Core 增量 Review — 2026-10-25

- 用户在 verifier 选择提示与成功 token 局部示例后完成核心段；Human Core 记录为提示 / 局部示例后实现，不记为独立实现。Codex 未修改用户 production 或测试。
- Scope MATCH；成功条件同时检查密码匹配与账户存在，成功 principal 只持有 userId，credentials 为 null、authorities 为空，finally 清除原始请求密码。
- Blocking finding：authenticate 的凭据拒绝分支使用 AuthenticationServiceException(INVALID_CREDENTIALS)，不符合已批准的 BadCredentialsException 合同；会把错误密码 / 未知账户 / 非法邮箱归入服务故障类型。交回用户修正该分支及 import，基础设施故障 helper 保持原分类。
- FRESH：仅运行 LocalPasswordAuthenticationProviderTests，11 项中 8 PASS、3 failures、0 errors / skipped，退出码 1；三项失败均为预期 BadCredentialsException、实际 AuthenticationServiceException。成功 token、Mapper 故障、hasher 故障 / 容量、初始化失败及脱敏等本类其余测试通过；mock 不证明真实数据库组合。
- 命令：根目录 mvnw.cmd -q -o -pl services/identity -am test '-Dtest=LocalPasswordAuthenticationProviderTests' '-Dsurefire.failIfNoSpecifiedTests=false'，沿用获准正常 Windows 权限。输出：services/identity/target/r1e-human-core-review.log；diff whitespace PASS。
- 本批 wider regression / PostgreSQL / package / 完整服务 / Flow 未执行或补齐；骨架批次 65/65 为 PRIOR。当前未完成整体验收，不提升模块整体 Ownership。

Current Gate: REVIEW_BLOCKED。Next Action：用户将凭据拒绝分支改用 BadCredentialsException，随后仅检查增量并重跑 Provider 定向测试。

### Human Core 修正与定向验证 — 2026-10-25

- 用户将凭据拒绝分支改为 BadCredentialsException(INVALID_CREDENTIALS)，并添加 import；基础设施 / 容量故障仍使用 AuthenticationServiceException。此前异常分类 finding 已解除。
- FRESH：相同 Provider 定向测试 11/11 PASS，0 failures / errors / skipped，退出码 0；diff whitespace PASS。输出：services/identity/target/r1e-human-core-correction.log。
- Codex 未修改用户 production 或测试，仅同步 Review / 验证记录；Human Core 保持提示 / 局部示例后实现，不自动提高模块 Ownership。
- 本批仅验证修正增量；既有 65/65 为 PRIOR，数据库 / 真实组件组合、package / 独立 JAR、整体 Review 与认证 Flow 尚未完成，不能称为 R1-E 整体验收 PASS。

Current Gate: REVIEW_PENDING。Next Action：在当前 R1-E 范围完成整体工程验收与必要认证 Flow，再进行代码导读 / Human Review；不进入下一 Slice。

### 整体 Review / 工程验收与 Flow — 2026-10-25

- 基线仍为 migrate/identity / c1be36de052b47ba2a08fc67e720548718105432。Scope MATCH：production 5 文件约 173 行，测试 2 文件新增 / 实质修改 212 行；未改 schema、dependency、公开入口、应用授权、安全配置、密码参数、旧 server 或用户 Human Core。
- 本轮未新增或修改 production / 测试代码；仅执行现有验证、新增认证 Flow、补关联 Flow 调用方说明与同步记录。新 Provider 测试文件的必要性与复用策略见上方预算，不增加测试工具或依赖。
- FRESH 第一批：Provider 11、normalizer 8、注册 Service 11、policy 7、blocklist 8、hasher 14、真实容量 8、HTTP 9，共 76/76 PASS、0 failures / errors / skipped，package 退出码 0；使用获准正常 Windows 权限。输出：target/r1e-full-review-package.log 与 r1e-full-review-reports.json。
- FRESH 第二批：一次性 PostgreSQL 18.6，缓存镜像 postgres:18，digest sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6；loopback 随机端口、tmpfs 与随机临时凭据，复用 deploy/local/identity/init-databases.sql。没有运行中的既有容器，未读取 / 修改旧业务库或既有卷。
- 显式启用 IDENTITY_DATABASE_TESTS=true，密码容量保持 1，从空 schema 执行 Flyway V1；隔离库标识由验证编排与现有 fixture 核对，应用使用非集群管理员 identity_app。
- 数据库新 / 既有组合：IdentityPasswordHashingIntegrationTests 2/2（含新增注册 → Mapper / Optional / constructor mapping → Provider / 真实 Argon2 认证，并确认撤销的应用准入不恢复、verifier 不变）；LocalRegistrationIntegrationTests 1/1、账户事务 4/4、Mapper / schema / 专用账号 3/3，共 10/10 PASS、0 failures / errors / skipped，package 退出码 0。

```powershell
.\mvnw.cmd -q -o -pl services/identity -am package '-Dtest=LocalPasswordAuthenticationProviderTests,LocalEmailNormalizerTests,LocalRegistrationServiceTests,LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
# 下一条只在已核对标识的一次性数据库环境执行，凭据只在当前进程内设置，不保存。
.\mvnw.cmd -q -o -pl services/identity -am package '-Dtest=LocalRegistrationIntegrationTests,LocalAccountPersistenceIntegrationTests,IdentityMapperIntegrationTests,IdentityPasswordHashingIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 两批独立运行，不将 86 项描述为一次命令的结果。第二批脱敏证据：target/r1e-final-verification-summary.json、r1e-database-package-verification.log、r1e-verification-cleanup.log（均在 services/identity/ 下，受 Git ignore）。
- 验证后 app_user / auth_identity / local_password_credential 各 5 行，app_access_grant 4 行；一条准入由新增认证组合有意撤销，认证未恢复。Flyway 成功记录 1 条，测试 trigger 遗留 0。容器 dailylanguage-r1e-review-d0e0193a76 已停止并自动移除，tmpfs 已清理，进程环境恢复；未输出或持久保存密码。
- Provider 验证前后 blob 均为 2476e0afd6af9da71f5f82f159532814991f7bf9；Codex 未修改用户核心实现。既有注册 blob b498eb26134012e91fb715c1443f7fd43eace94a 保持不变。
- 新增并登记 [内部凭据认证 Flow](../flow/identity-local-authentication.md)，仅补充密码 Flow 的当前调用方说明。Flow 来自 source 与上述证据，不把未来 HTTP / Session / 跨服务行为写成已实现。
- 自查结果：Code Review PASS，无 blocking finding；账户存在性与密码匹配分离、dummy 不产生身份、查询与规范化 catch 分离、安全异常 / 日志、finally 清理、最小 principal 与只读 SQL 符合合同。Architecture 边界符合批准范围；concrete composition，Extensibility Fit RIGHT_SIZED。此自查不冒充独立作者 Review。
- NOT_RUN：独立 JAR JVM / 双启动、公开登录 / Session、browser / 旧 server / 跨服务、生产容量、防枚举耗时与 live Provider；真实 Spring 测试启动不替代独立部署验收。没有这些范围的 PASS 或新 Ownership 等级结论。
- Human Review 入口：authenticate 的两个条件与成功 token；lookupCredential 的两个 catch 范围；verifySubmittedPassword 的容量 / 故障映射；数据库组合对撤销应用准入的断言。用户实现与异常分类讨论可作局部 evidence，整个 Slice 的用户 Review 尚未确认，不安排固定问答或自动标记 UNDERSTOOD。

Engineering Acceptance: PASS（仅 R1-E 内部凭据认证及相关回归）。Behavior Flow: CURRENT。
Current Gate: OWNERSHIP_PENDING。Next Action：用户结合真实代码与认证 Flow Review 当前 R1-E；不自动 commit / push 或进入下一 Slice。

### User Review / Ownership 收口 — R1-E，2026-10-25

- 用户完成代码导读并自行查看实现后表示，部分判断初看感觉多余，整体看过后接受保留，未提出待修问题；本 Slice Human Review 已接受。
- Ownership evidence 来自用户实际实现 / 修正核心判定、对凭据拒绝与服务故障分类的提问和修正，以及本次真实代码 Review 接受；不只以测试通过或笼统同意判定。结论限定于 R1-E 当前凭据认证与失败边界，不升级模块整体等级，不记作独立实现。
- 当前 Provider blob 为 3fc6e7749ced047c5d635889ffad2b4f6c68ae25；与已验收的 2476e0afd6af9da71f5f82f159532814991f7bf9 逐 blob 对比，仅删除 3 行 Human Core 说明并留空行，逻辑没有变化。保留用户修改，不重跑无行为变化的测试。
- 本次只同步收口记录；76/76、数据库 10/10 两批与 package 成功保留为同日 PRIOR 证据，不标为本轮 fresh。diff whitespace PASS，认证 Flow 仍与当前行为一致。
- 工程取舍：沿用现有 AuthenticationProvider、Mapper 和受限 hasher，分别处理输入拒绝、查询故障和计算故障；代价是 helper 增加初次阅读成本。用户借助普通 if 与局部示例完成核心段，之后通过真实异常分类修正和调用链导读完成本次 Review，不增加新 abstraction 或固定问答。

Human Review: COMPLETE（仅 R1-E）。R1-E Gate: COMPLETE / COMMITTED `8630e6f`。
2026-10-25 提交状态同步：用户已提交 8630e6fddefaad53acc01dc721c757705a17c1a5；提交共 12 文件，Provider blob 与收口候选一致，本次未重跑测试或重开 Review。
Next Action：下一 Slice 进入 Design / Scope；建议 R1-F 先迁入 Redis Session 基础，再单独设计公开 login / logout / me。当前提案与实现分工仍待用户决定，不生成代码骨架。

## R1-F — Redis Session 基础

### 已批准范围与取舍

2026-10-25 用户回复“ok，执行”，批准本 Slice、新 Session Redis starter、独立本地 Redis 与 Codex 完整实现分工；A — Critical。
测试预计 160–220 行、production 70–100 行，可能超过两倍，用户已一并批准该预算；不强制安排 Human Core。
R1-E 仅产生单次调用的认证结果，本 Slice 增加框架管理的 Session 存取基础；先分离存储验证，再单独实现公开 login / logout / me。
替代方案是一次接通 Session 与公开接口，但会同时引入 rotation、CSRF 与 HTTP 错误映射，本次不采用。

- 引入 Boot-managed spring-boot-starter-session-data-redis，不单独指定 Session / Redis client 版本；JAR 核对 Spring Session / Data Redis 4.1.1、Lettuce 7.5.2.RELEASE。
- SessionConfiguration 只提供固定 springSessionDefaultRedisSerializer bean。Jackson JSON 使用 SecurityJacksonModules，只额外允许 final record AuthenticatedAccount；不扩大到整个 package，不修改该 record 或 R1-E Provider。
- 复用 Boot 自动配置、default 非索引 RedisSessionRepository；namespace daily-language:identity:session:v1、24h idle TTL，连接 / 操作 timeout 1s。Redis host 显式由 IDENTITY_REDIS_HOST 提供，端口默认 55442。
- Cookie 默认 SESSION / HttpOnly / Secure / SameSite=Lax / Path=/，无 Domain / Max-Age；未来本地 HTTP 仅通过显式 IDENTITY_SESSION_COOKIE_SECURE=false 覆盖，当前只验证配置绑定。
- 保存 Redis 失败传播 DataAccessException，无内存 repository fallback。JSON 未许可类型还原失败，不退回 Java native serialization；serializer 不承担任意 Session 属性的敏感字符串过滤。
- 当前 HTTP SecurityConfiguration 保持 STATELESS 与已有拒绝规则，不创建登录会话。生产 authenticate 到 Session 的写入尚未接通；当前存取入口是工程验证，不宣称 HTTP failure handler、rotation 或浏览器会话已经实现。
- 本地新增 Redis 7.2.16-alpine3.21（旧根 Compose / server 不动）；官方 Open Source 7.2 分支维护与镜像依据见 deploy README。loopback / tmpfs、禁用 RDB / AOF，重启丢失 Session；不作为生产 Redis 部署 / 凭据 / TLS / 高可用 / 持久化方案。

Architecture / Security Impact：增加获批的 Session Redis dependency、运行依赖与受限 JSON 还原边界；不创建自定义 Session abstraction / repository / filter / key lifecycle，不使用 @EnableRedisHttpSession。
Non-scope：公开注册 / login / logout / me、Session ID 轮换 / 浏览器过期 / 退出链路、Rate Limit、Gateway、Dubbo / Nacos / mTLS、应用准入 / 账号启停、前端、旧 server、schema / 事务 / 密码机制或其他 dependency 升级。

### 文件、规模与验证策略

Production 4 文件、约 73 行：新增 security/SessionConfiguration.java（35 行），修改 services/identity/pom.xml（4）、application.yml（21）、deploy/local/identity/compose.yaml（13）。
测试 4 文件、约 167 行：新增 SessionSerializationTests（47）、RedisSessionIntegrationTests（80）、RedisSessionUnavailableIntegrationTests（37），修改既有 IdentityBoundaryHttpTests（约 3 行）。均在原预算内，未增加测试依赖、基类或 fixture 工厂。
三个新文件分别验证纯 JSON 类型边界、真实 Redis 状态恢复、独立不可用地址的故障行为；共 4 个代表性测试，不复制旧 server 全套或穷举输入。
复用现有 IdentityBoundaryHttpTests.TestApplication，仅将 SessionConfiguration 加入 import；HTTP 测试显式给本机 Redis host 并关闭其最小上下文的 Redis health，不连接实际 Redis。真实存储 / 故障由显式开启的 integration 证明。
文档：本合同、PROJECT_STATUS、本地 README、Session Flow 与 Flow index；无 Schema / API / provider 修改。

### Verification / 自查 — 2026-10-25

- 初始离线编译因新增 starter 未缓存退出 1；获准下载 Boot 管理依赖后 test-compile 成功。未改版本或增加替代 dependency。
- 首轮定向测试 11 项：JSON 2/2 PASS、HTTP 9 errors，原因是默认权限阻止本机连接导致 Tomcat 无法启动；获准正常 Windows 权限重跑后 11/11 PASS、0 skipped。没有修改生产或测试逻辑掩盖错误。
- FRESH 第一批：显式 IDENTITY_REDIS_TESTS=true / IDENTITY_REDIS_UNAVAILABLE_TESTS=true，新 JSON 2、真实 Redis 1、不可用 1、既有 Provider / 组件 / Service / HTTP 76，共 80/80 PASS、0 failures / errors / skipped，package 退出码 0。
- 真实 Redis 第二 repository 读取同一随机 Session，身份 UUID / authenticated / null credentials 保持；独立 namespace 与 24h metadata、实际 key TTL（23–24h 区间）通过；Cookie 安全配置绑定通过。finally 删除后 key 不存在，整次实例 DBSIZE=0。
- FRESH 第二批：一次性 PostgreSQL 18.6、隔离标识 / identity_app、空 schema Flyway V1、密码容量 1；既有真实注册 / 认证组合 3、账户事务 4、Mapper / 权限 3，共 10/10 PASS、0 failures / errors / skipped，package 退出码 0。两批分开记录，不描述为单次 90 项运行。

```powershell
.\mvnw.cmd -q -o -pl services/identity -am package '-Dtest=SessionSerializationTests,RedisSessionIntegrationTests,RedisSessionUnavailableIntegrationTests,LocalPasswordAuthenticationProviderTests,LocalEmailNormalizerTests,LocalRegistrationServiceTests,LocalPasswordPolicyTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,PasswordHashConcurrencyGateTests,IdentityBoundaryHttpTests' '-Dsurefire.failIfNoSpecifiedTests=false'
# 上一条需独立 Redis 环境及显式 integration 开关；下一条复用 R1-E 数据库回归命令和隔离库要求。
.\mvnw.cmd -q -o -pl services/identity -am package '-Dtest=LocalRegistrationIntegrationTests,LocalAccountPersistenceIntegrationTests,IdentityMapperIntegrationTests,IdentityPasswordHashingIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- Redis digest sha256:f8548b29a1f1cb358b5fcfec1e0126beab57ad15776c1e87623f865579f16c5a；新容器 dailylanguage-r1f-redis-dbcd59e420 与 PostgreSQL dailylanguage-r1f-review-98c6a88cf3 均停止 / 自动移除，tmpfs 清理、进程环境恢复。无旧实例 / 既有卷操作，无 FLUSHDB / FLUSHALL，随机数据库密码未输出或保存。
- 脱敏证据在 services/identity/target/：r1f-redis-verification-summary.json、r1f-session-module-package.log、r1f-final-verification-summary.json、r1f-database-package-verification.log 与对应 cleanup log。SessionConfiguration blob 29f0694464e57a4b20187075a58077192e6cca4e；R1-E Provider 保持 3fc6e7749ced047c5d635889ffad2b4f6c68ae25。
- 额外检查：Compose config --quiet 退出 0（只用合成占位密码），JAR 依赖与管理版本一致；diff whitespace PASS。已创建 / 登记 [Session Storage Flow](../flow/identity-session-storage.md)，依据真实 source 与本批证据。
- 自查：Scope MATCH，Code Review PASS，无 blocking finding；精确业务类型许可、框架生命周期、独立 namespace / 超时与存储故障不回退符合合同；concrete framework configuration，Extensibility Fit RIGHT_SIZED。Codex 自查不记作独立作者 Review。
- NOT_RUN：独立 JAR JVM、生产 Redis 健康故障响应、真实 24h 等待过期 / Redis 重启恢复、浏览器 Cookie 实际发送、公开登录 / Session rotation / logout、跨服务认证、生产容量与部署；Cookie binding / TTL 读值不等于这些场景已验收。

### 分工与 Human Review 入口

本 Slice 由 Codex 实现和验证，不记为用户 hands-on；现有模块掌握等级不变。
Learning Focus：类型许可与 Session 内容之间的边界；Cookie / 框架 Session 存储与生产认证入口是不同职责。
User Review 入口：SessionConfiguration serializer bean / AuthenticatedAccount 精确许可；application.yml 的 namespace / idle TTL / Secure；真实 Redis 与不可用测试的证明和局限。
Engineering Acceptance: PASS（仅 Session 存储基础及相关 Identity 回归）。Behavior Flow: CURRENT。
Current Gate: OWNERSHIP_PENDING。Next Action：用户 Review 当前 R1-F 实现与 Session Flow；不自动 commit / push 或进入下一 Slice。

### User Review 收口 — R1-F，2026-10-26

- 用户针对没有 Human Core、Slice 是否只做序列化提出疑问；讨论明确本次采用 Codex 完整实现分工，包含依赖、Redis repository 自动配置、JSON 类型许可、namespace / TTL / Cookie 配置与存取 / 故障验证，尚未接通生产登录写 Session。用户随后表示“ok没问题了”，接受当前 Slice。
- 用户未选择接管测试的备选分工，不删除或改写已完成测试，不补记 Human Core。此记录确认当前范围与实现的 Human Review 接受，不将笼统同意推断为序列化安全或 Spring Session 生命周期的独立掌握，不更新模块等级或记为用户 hands-on。
- 当前仍为 migrate/identity / 8630e6fddefaad53acc01dc721c757705a17c1a5，尚未提交 R1-F。核对 SessionConfiguration blob 29f0694464e57a4b20187075a58077192e6cca4e 与 R1-E Provider 3fc6e7749ced047c5d635889ffad2b4f6c68ae25，均与验收记录一致；diff whitespace PASS。
- 本轮只同步 Review 收口记录，未修改 production / 测试，未重跑验证；2026-10-25 的 80/80、数据库 10/10 两批及 package PASS 为 PRIOR，不标为本轮 fresh。
- 工程取舍：通过框架提供 Session 存取生命周期，业务新增 Java 主要是 serializer 边界；代价是新增 Redis 运行依赖，以及必须分清存储基础与后续 HTTP 登录生命周期。后续公开接口仍需独立 Slice Contract，不以本次 Review 扩大授权。

Human Review: COMPLETE（仅 R1-F）。R1-F Gate: COMPLETE / COMMITTED `dc8df93`。
2026-10-26 提交状态同步：用户已提交 dc8df93a2aaf7dc4601d3e207907792db28ed611，SessionConfiguration 与验收 blob 一致；本次未重跑测试或重开 Review。
Next Action：下一 Slice 进入 Design / Scope，建议 R1-G 接通 HTTP login / logout / me 与 Session lifecycle；实现范围、测试预算与 Human Core 分工待用户决定，不直接生成代码。

## R1-G — HTTP 认证与 Session 生命周期

### 已批准范围与行为合同

2026-10-26 用户回复“确认实施”，批准 A 类 R1-G：4 个 production 文件、预计 190–240 行，测试预计 280–360 行；用户实现登录失败 handler，Codex 实现框架衔接、其余代码与验证。
Current Situation：R1-E 提供凭据认证，R1-F 提供 Session 存储，但此前没有生产 HTTP 认证入口。
目标：通过 Spring Security form login / logout 与 Spring Session 形成一次可独立 Review 的 HTTP 闭环；不在 Controller 手写认证或 Session 生命周期。
相比只开放 login，包含 logout / me 才能验证会话恢复与失效，代价是增加 CSRF 和存储故障边界；用户已确认这个完整小闭环。

| 接口 | 成功 | 拒绝 / 故障 |
|---|---|---|
| POST /api/auth/login，form-urlencoded email / password | 204，无跳转；认证结果仅 userId，credentials=null；框架保存 Redis Session，已有 Session ID 轮换 | 凭据拒绝 401 INVALID_CREDENTIALS；认证服务故障 503 AUTHENTICATION_UNAVAILABLE；缺失 / 错误 CSRF 403 |
| GET /api/auth/me | 200，仅 {userId}，读取已认证 principal，客户端 userId Header 不具 authority | 匿名 / 失效 Session 为 401 UNAUTHENTICATED；已知 Session 存储故障为固定 503 |
| POST /api/auth/logout | 204，仅当前 Session 失效；重复匿名 logout 在有效 CSRF 下仍可 204 | 缺失 / 错误 CSRF 403；已知 Session 存储故障为固定 503 |

- 使用现有 SPA CSRF 机制：GET health 等安全请求生成可读 XSRF-TOKEN，写请求回传 X-XSRF-TOKEN；SESSION cookie 仍为 HttpOnly。CSRF Cookie 的 Secure 与当前显式环境配置一致。
- Session policy 从 STATELESS 改为 IF_REQUIRED，使用框架 changeSessionId；默认 HTML 登录页、request cache 与 HTTP Basic 关闭。只允许 auth/me 读取已认证身份，其余未批准入口仍拒绝，注册未开放。
- AuthenticationHttpResponseWriter 由安全 handler 与 SessionStorageFailureFilter 共同使用，输出固定 code JSON，不接受原始异常作为输出；设置 no-store / nosniff，不返回 credential、SQL、Redis 地址或 cause。
- SessionStorageFailureFilter 位于框架 SessionRepositoryFilter 外层，只作用 login / logout / me；转换 RedisConnectionFailureException、QueryTimeoutException、Redis SerializationException。其他 RuntimeException 不伪装为存储故障；已提交响应继续抛出，不承诺可重新写成 503。
- Session 错误转换不手写存取、Session ID、Redis key 或 Cookie 删除；resetBuffer 保留已有安全响应头。503 不承诺超时写入一定未发生，不自动重试或新增会话回滚机制。
- 无新 dependency / schema / module，复用 R1-E Provider 与 R1-F serializer / Redis / Cookie。保持应用绑定 loopback，本 Slice 不宣称公网部署准备完成。
Non-scope：公开注册、应用准入 / 账号启停、Rate Limit、Gateway / Dubbo / Nacos / mTLS、跨服务、前端 / 浏览器、生产部署、remember-me / logout-all / 最大会话数及旧 server 修改。

### 文件、测试预算与分工

Production 4 文件：修改 security/IdentitySecurityConfiguration.java，新增 CurrentAccountController.java、AuthenticationHttpResponseWriter.java、SessionStorageFailureFilter.java。
骨架新增 / 实质修改约 154 行（3 个新文件 125 行 + 安全配置约 29 行），核心完成后仍预计在 250 行内；实际小于最初预算，不增加无用代码填充。
测试优先扩展 IdentityBoundaryHttpTests（约 57 行）、RedisSessionUnavailableIntegrationTests（约 63 行）；仅新增 account/IdentityAuthenticationHttpIntegrationTests（129 行），合计约 250 行，不超过 production 两倍，无新测试依赖 / 基类 / fixture 工厂。
新文件必要性：现有 repository 组合测试不证明真实 HTTP、Cookie、Session ID 轮换与 logout；此文件复用既有数据库 fixture，在真实 PostgreSQL / Redis / Tomcat 上验证生产闭环，不复制底层事务失败 / 并发用例。
边界测试复用现有最小 Web 配置；test-only AuthenticationProvider 只产生两种失败样本。Redis 故障测试用 Mockito 覆盖该 bean，模拟认证成功后遇 Session 保存失败，不记作真实凭据认证证据。

### Human Core — 登录失败 handler

- 用户编辑边界：IdentitySecurityConfiguration.identitySecurityFilterChain 内 formLogin.failureHandler lambda，替换 UnsupportedOperationException 占位，预计 5–10 行。
- 已提供参数：request、response、exception；已注入 responses，可调用 writeInvalidCredentials(response)、writeAuthenticationUnavailable(response)。必要的异常类型 import 由用户补充。
- Acceptance Criteria：将 AuthenticationServiceException 表示的服务故障映射为固定 503；凭据拒绝映射为固定 401；不返回或记录 exception.getMessage / cause，不重试、不改写密码、不修改其他框架配置。
- 用户只完成这个实际失败行为判断；Codex 不提前提供完整替换答案，不修改用户方法。Codex 负责其余三个组件、HTTP / Session 配置与所有获准测试 / 工程验证。
- Learning Focus：Java 内部异常类型如何变成外部 HTTP 合同；认证成功与 Session 成功保存之间的边界。
- User Review 入口：failureHandler；SessionStorageFailureFilter 的顺序、捕获类型、isCommitted 与 resetBuffer；真实 HTTP 组合对 Session / principal / logout 的断言。

### Verification — 2026-10-26 骨架阶段

- FRESH：全部测试源码离线 test-compile 成功，退出 0；包含尚未运行的真实 HTTP / PostgreSQL / Redis 组合。
- FRESH：仅定向运行 IdentityBoundaryHttpTests 11 项（9 PASS、2 failures）、RedisSessionUnavailableIntegrationTests 2/2、SessionSerializationTests 2/2；共 15 项、13 PASS、2 failures、0 errors / skipped，退出码 1。
- 两项 RED 均为有合法 CSRF 的认证失败请求进入 Human Core 占位：预期 401 / 503，实际 500，运行日志包含对应固定 UnsupportedOperationException。没有将占位改成成功预期。
- 通过部分证明真实 Tomcat CSRF 缺失 / 错误拒绝、匿名及伪造 Header 不产生身份、JSON 类型边界、Redis 保存 / 恢复失败为固定 503、保留 nosniff、响应无 Redis 地址或凭据细节；故障 HTTP 使用 mock 成功认证，不证明真实凭据链。

```powershell
$env:IDENTITY_REDIS_UNAVAILABLE_TESTS='true'
.\mvnw.cmd -q -o -pl services/identity -am test '-Dtest=IdentityBoundaryHttpTests,RedisSessionUnavailableIntegrationTests,SessionSerializationTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 使用获准正常 Windows 权限；本阶段不启动真实数据库或 Redis 容器。证据：services/identity/target/r1g-test-compile.log、r1g-scaffold-focused.log 与 surefire XML。
- NOT_RUN：新真实 HTTP / PostgreSQL / Redis 成功闭环、Session ID 轮换 / 当前会话 logout / Cookie 实际行为、真实凭据到 Session 组合、其余组件回归、package / 独立 JAR、浏览器 / 跨服务 / 生产。R1-E / F 历史 PASS 为 PRIOR，不替代本 Slice 验收。
- 当前仅骨架自查，不能称工程验收 PASS。完整认证 Session Flow 待核心实现与真实链验证后再新增；既有 Flow 只保留其已验收行为并标明 R1-G 骨架边界。

2026-10-26 历史 Gate: HUMAN_CORE_READY；当时等待用户实现 handler，当前状态见下方增量 Review。

### Human Core 示范与增量 Review — 2026-10-30

- 用户明确请求 Codex 完整示范，随后写入实现；分工调整仅限失败 handler，记录为参考完整示范后写入，不记独立实现或自动提高 Ownership。
- 实际 Diff 仅 SecurityConfiguration 的 import 与 failureHandler：AuthenticationServiceException 映射固定 503，其余认证拒绝映射固定 401；没有异常正文 / cause 输出、重试、密码修改或 Session 操作。增量 Review 无阻塞问题，Scope MATCH；Codex 未修改用户 production / 测试。
- FRESH：IdentityBoundaryHttpTests 11/11 PASS，0 failures / errors / skipped，退出 0；包括原占位 RED 的 401 / 503 两项及既有 CSRF / 匿名身份 / 端点拒绝边界；diff whitespace PASS。
- 首次定向运行因 SocketException: Operation not permitted 无法启动 Tomcat（11 errors），随后获准正常权限重跑通过；未改测试或配置。命令：`mvn -q -o -pl services/identity -am test -Dtest=IdentityBoundaryHttpTests -Dsurefire.failIfNoSpecifiedTests=false`。
- 证据：`services/identity/target/surefire-reports/TEST-com.dailylanguage.identity.security.IdentityBoundaryHttpTests.xml`；本轮日志 `/tmp/dailylanguage-r1g-user-handler-review.log` 为临时文件。
- NOT_RUN：真实 HTTP / PostgreSQL / Redis 成功闭环、Session 轮换 / 恢复 / logout、其余组件回归、package / 独立 JAR、浏览器 / 跨服务 / 生产；Redis 故障与 JSON 骨架通过证据仍为 PRIOR。完整认证 Flow 待真实链验证后补，不能称 R1-G 整体验收 PASS。

2026-10-30 增量 Review 历史 Gate: REVIEW_PENDING；随后用户批准闭环验收，当前结果见下方。

### 闭环验收、Cookie 定位与获准修正 — 2026-10-30

用户明确要求“继续下一步，闭环验收”；本轮验证仅覆盖已批准 R1-G 与 Identity 回归，不运行旧 server / 前端 / 跨服务。

#### 失败、Evidence 与修正决定

- 首批 focused HTTP / Session 共 17 项：16 PASS、1 failure、0 errors / skipped，退出 1；真实登录返回 204，但成功链测试在“新 Session ID 不等于旧 ID”处失败，后续 logout 断言当时未执行。停止扩大测试并定位。
- 测试通过 `new HttpCookie` 手造匿名 Session Cookie：domain=null、version=1；服务器响应经 CookieManager 解析为 domain=127.0.0.1、version=0。独立 Java 小实验确认 Cookie 容器保留两个同名 Cookie，findFirst 返回旧值，后续请求携带两者。
- 真实服务诊断第一轮：服务器返回的新 ID 与旧 ID 不同，新 Redis Session 存在、携带新 Cookie 的 me=200，但手造 Cookie 未恢复预设匿名 Session，旧 ID 仍存在；不能据新 Cookie 单独宣称旧 Session 已正确轮换。
- 第二轮只在临时诊断客户端按 Set-Cookie 格式解析匿名 Cookie：同一生产代码下 rotated=true、旧 Redis ID 消失、新 Redis ID 存在、同名 Cookie 数量=1、findFirst 不再指向旧值、me=200。根因确认在测试输入，不在生产 Session 配置；诊断不是正式测试 PASS 的替代证据。
- 用户选择参与判断，并明确批准“允许修正测试并继续验收”。Codex 仅修正 IdentityAuthenticationHttpIntegrationTests 的匿名 Cookie 构造为 CookieManager.put 解析 Set-Cookie；保留全部原有断言，新增 SESSION Cookie 唯一性断言。生产、用户 handler、其他测试与环境配置未改。

#### 最终验证与环境

| 独立批次 | FRESH 结果与证据范围 |
|---|---|
| 其余 Identity regression（在测试修正前独立运行） | 77/77 PASS，0 failures / errors / skipped，退出 0：normalizer 8、policy 7、blocklist 8、hasher 14、capacity 8、注册 Service 11、Provider 11、Mapper 3、账户事务 4、密码组合 2、注册组合 1；其中数据库 10 项真实运行 |
| 修正后 focused HTTP / Session | 17/17 PASS，0 failures / errors / skipped，退出 0：真实 HTTP 成功链 1、HTTP 边界 11、真实 Redis Session 1、不可用 Redis 2、JSON serializer 2 |
| package / JAR | 两批各执行 `package -DskipTests`，均退出 0；不重复运行已完成测试。JAR 包含四个 R1-G 组件及 Session / Data Redis 4.1.1、Lettuce 7.5.2.RELEASE；未启动独立 JAR JVM |
| source / 清理 | 用户 handler blob 始终为 8a5122fd15ae10ff9c2a3d1c2427492074cecb6d，生产源码未改；每批运行前后 source snapshot 一致；无测试 trigger / Redis key 遗留，本轮全部容器及 tmpfs 已清理，diff whitespace PASS |

上述是两批独立通过的 77 项与 17 项，共覆盖 94 项，不描述为一次 94 项命令。
本次仅测试 fixture 有变，不重跑已通过且不依赖该 fixture 的 77 项；此前 16 PASS / 1 failure 及两次诊断结果保留。

隔离环境：Docker Engine 29.9.1；PostgreSQL 18.6、Redis 7.2.16；仅绑定 127.0.0.1 的动态端口，数据为 tmpfs，无既有业务库 / 卷操作，无 FLUSHDB / FLUSHALL。
每批新建空 public schema，使用既有 init-databases.sql 初始化隔离标识及 identity_app，显式开启环境开关，密码计算容量保持 1；凭据随机且仅在运行时使用，未打印或持久保存。
PostgreSQL 镜像 digest：`sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`；Redis 镜像 digest：`sha256:ee061d86c25773048211e99f00d63b47b9ecfe28560113e16cce2c962d5bec1c`。

- regression 批结束：app_user / auth_identity / local_password_credential 各 5，app_access_grant 4（认证组合有意撤销一条），Flyway 成功 1、测试 trigger 0、Redis key 0。
- focused 批结束：四张账户表各 1，Flyway 成功 1、测试 trigger 0、Redis key 0。
- 证据目录（均在忽略的 target 下）：`r1g-20261030-verification/`（原失败）、`r1g-20261030-cookie-diagnostic/`、`r1g-20261030-cookie-normalized-diagnostic/`、`r1g-20261030-remaining-regression/`、`r1g-20261030-focused-fixed/`；包含运行日志、JUnit XML 或诊断日志与脱敏 summary.json。

在上述随机隔离环境中，显式设置 IDENTITY_DATABASE_TESTS / IDENTITY_REDIS_TESTS / IDENTITY_REDIS_UNAVAILABLE_TESTS / IDENTITY_HTTP_AUTH_TESTS=true，执行：

```sh
mvn -q -o -pl services/identity -am test -Dtest=IdentityMapperIntegrationTests,IdentityPasswordHashingIntegrationTests,LocalAccountPersistenceIntegrationTests,LocalEmailNormalizerTests,LocalPasswordAuthenticationProviderTests,LocalPasswordBlocklistTests,LocalPasswordHasherTests,LocalPasswordPolicyTests,LocalRegistrationIntegrationTests,LocalRegistrationServiceTests,PasswordHashConcurrencyGateTests -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -o -pl services/identity -am test -Dtest=IdentityAuthenticationHttpIntegrationTests,IdentityBoundaryHttpTests,RedisSessionIntegrationTests,RedisSessionUnavailableIntegrationTests,SessionSerializationTests -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -o -pl services/identity -am package -DskipTests
```

#### 自查与 Human Review 边界

- Code 自查：无 blocking finding，Scope MATCH；测试修正未降低 rotation / Redis 旧 ID 失效 / principal / logout / Cookie 断言。该自查不冒充独立作者 Review。
- [HTTP 认证与 Session Flow](../flow/identity-http-authentication.md) 已按源码及本次验证建立并加入索引；Provider / Session 存储 Flow 与本地 README 已同步当前边界，Review Sync Status: CURRENT。
- Human Review Focus：failureHandler 的异常分类；框架 changeSessionId、保存 SecurityContext 与设置 204 的职责；外层 SessionStorageFailureFilter 的顺序、已提交响应与已知故障范围；me 的 principal authority；logout 仅当前会话失效；测试 Cookie 样本为何必须模拟真实服务器格式。
- NOT_RUN：浏览器、Hosted HTTPS、独立 JAR JVM、跨服务、真实多设备分别登录、24h 等待过期、QueryTimeout / SerializationException / 已提交响应逐项故障注入、生产容量及提交确认丢失；不能据本机测试扩展为上述结论。
- Human Core 保持“参考 Codex 完整示范后写入”；用户愿意参与 Cookie 根因判断，不等于已经完成设计判断 / Ownership，待结合真实代码讨论，不自动提高模块等级。

### Human Review 进行中 — 2026-10-30

- 用户在 SecurityFilterChain 导读中逐步追问 handler 的 204、Filter 顺序与异常捕获范围、changeSessionId、响应提交，以及 Filter 经 writer 设置 503 的实际代码位置；在该段源码追踪后表示理解，并明确要求将学习写入文档。
- 已追加 [LEARN-003](../ownership/LEARNING_LOG.md#learn-003--登录认证session-持久化与-http-响应提交的失败边界)，保留 PROVISIONAL 与真实分工：handler 参考完整示范后写入，框架核对 / 测试由 Codex 执行；不据此推断模块独立掌握或整个 R1-G Human Review 完成。
- 后续 Review 聚焦 me 的 Session / SecurityContext / principal 来源与 Header 无 authority，再看 logout 的当前会话失效及其他会话保留；本轮文档记录未重跑测试，沿用同日 PRIOR 验收。

上述导读后，用户提出恢复旧 server 的功能内分层，并确认先整理 security 五个 production 文件；当前增量与 Gate 见下方。

### security 分层整理 — 已批准范围与验收，2026-10-30

- 用户确认按旧项目“功能模块 → 职责分层”整理；本轮只迁移 security 的五个 production 文件，测试同步引用。account、credential 与公共 persistence 后续另行定范围；不新增抽象 / 依赖，不改 HTTP、数据库、认证规则、Filter 顺序、Session 生命周期或异常合同。
- 文件映射：CurrentAccountController → security/api；IdentitySecurityConfiguration、SessionConfiguration → security/config；SessionStorageFailureFilter、AuthenticationHttpResponseWriter → security/infrastructure。测试 IdentityBoundaryHttpTests 增加显式 import；SessionSerializationTests 随配置迁入 security/config，以保留 bean 方法的 package-private 可见性。其他跨层集成测试留在 security 父包，不调整测试类 / 方法可见性。
- AuthenticatedAccount 的全限定名与 serializer 类型许可不变，避免本次目录整理同时改变 Redis Session 中的业务身份类型；Mapper XML、扫描配置、application.yml 与账号 / 密码代码均未修改。
- 范围自查：相对本轮源码快照，逐文件排除 package / import 与空行后，所有 production / test 方法正文完全一致；用户 handler 的原始判断保持不变。Scope MATCH，无逻辑修复或断言降低。
- FRESH：`mvn -q -o -pl services/identity -am clean test-compile` 退出 0；在 clean 前将既有 target 日志 / 摘要 / 验收目录备份到临时目录，随后恢复历史证据目录。当前 surefire-reports 由新测试生成，避免混入旧 package 报告或编译类。
- FRESH：新目录下 IdentityAuthenticationHttpIntegrationTests 1、IdentityBoundaryHttpTests 11、RedisSessionIntegrationTests 1、RedisSessionUnavailableIntegrationTests 2、SessionSerializationTests 2，共 17/17 PASS，0 failures / errors / skipped，退出 0；package（跳过已完成测试）退出 0。沿用本节前次 focused 命令，不重跑不依赖本次迁移的其余 77 项；后者为同日 PRIOR，不写成当前全 94 项 fresh。
- 环境：一次性 PostgreSQL 18.6 / Redis 7.2.16、loopback 动态端口 / tmpfs、既有隔离标识与独立账号、空 schema Flyway V1、密码容量 1；测试 trigger / Redis key 遗留 0，两容器均已删除；未操作既有业务库 / 卷。
- JAR 检查：五个类均在新路径，旧 security 根包类不存在，AuthenticatedAccount 仍位于 account 原路径；Session / Data Redis 依赖版本未变。证据：忽略的 `services/identity/target/r1g-20261030-security-layout/` 中日志、JUnit XML 与 summary.json；clean 编译日志备份在 `/tmp/dailylanguage-security-layout-20261030/clean-test-compile.log`。
- 受影响 Flow 与 LEARN-003 的源码链接已迁移，Flow 的行为合同不变；本次没有新增学习掌握或独立作者 Review 结论。

上述五文件边界随后被用户明确扩大为全部剩余功能；先完成完整分层再继续 Review，当前决定与验收见下方。

### Identity 完整分层与 Session v2 — 已批准范围与验收，2026-10-30

#### 用户决定与兼容性

- 用户澄清所有功能的新增与存量代码都应遵循项目既有职责分层，并要求其他功能一并迁移后继续 Review。本轮据此完成 account、credential、persistence 及测试；这是获准的集中目录迁移，不将它解释为新增业务能力或重写既有逻辑。规则已保存至全局 / 项目 AGENTS，后续按实际职责归层，不创建空层或额外抽象。
- 实测当前 serializer 会把 AuthenticatedAccount 的完整类名写进 Redis JSON，因此仅改变 import 不足以保证旧会话兼容。用户在影响说明后明确选择“接受迁移并使旧 Identity 会话失效”：AuthenticatedAccount 从 account 移到 account/domain，Session namespace 从 `daily-language:identity:session:v1` 改为 `daily-language:identity:session:v2`。
- 当前版本不恢复 v1 会话，用户需要重新登录；账户 / 密码 / 应用准入数据不变，不批量删除旧 Redis key，旧会话依原 TTL 过期。未实现旧类名 alias、会话数据转换或跨版本会话兼容，不保留一个未分层的旧身份类冒充完成迁移。

#### 当前职责与文件边界

| 路径 | 职责 / 类型 |
|---|---|
| account/application | LocalRegistrationService、LocalRegistrationException |
| account/domain | LocalEmailNormalizer、AuthenticatedAccount |
| account/infrastructure | LocalAccountPersistence、LocalAccountMapper、LocalPasswordCredential、LocalPasswordAuthenticationProvider |
| credential/domain | LocalPasswordPolicy、LocalPasswordBlocklist；沿用旧 server 的规则归属 |
| credential/infrastructure | LocalPasswordHasher、PasswordHashConcurrencyGate、PasswordHashCapacityExceededException |
| persistence/infrastructure | PostgreSqlUuidTypeHandler |
| security/api、config、infrastructure | 保留前一轮已整理的五文件结构；Controller / Session 配置同步 principal import |

本轮迁移剩余 14 个 production 文件、16 个测试 / helper 文件；测试随职责归层，跨层 HTTP / 数据库 / Redis 组合放 infrastructure，SessionSerializationTests 保留 config。IdentityApplication 保留服务根包，其余功能根包不再平铺 Java 类。
Mapper XML 的 namespace / resultMap 类型与 application.yml 的 type-handlers-package 同步更新。Mapper、凭据 record、Provider 构造器及测试 helper 仍保持原可见性；未为跨包调用扩大访问范围、添加 Interface、改变事务、密码规则或失败分类。

#### Verification 与自查

- FRESH：`clean test-compile` 与补充测试后的 `test-compile` 均退出 0；clean 前备份历史 target 日志、报告与验收目录，随后恢复历史证据目录，当前报告由新包下的测试重新生成，旧编译类无残留。
- FRESH：focused HTTP / Session 18/18 PASS（HTTP 成功链 1、HTTP 边界 11、真实 Redis 2、不可用 Redis 2、JSON 2）；其余 Identity regression 77/77 PASS（其中真实数据库 10 项），两批均 0 failures / errors / skipped、退出 0。共覆盖当前 Identity 全部 16 个测试类的 95 项，不描述成一次 95 项命令。
- 新增 1 个真实 Redis 回归 `ignoresPreviousNamespaceWithoutDeletingItsStoredSession`：在 v1 namespace 保存含旧 principal 类名的合成 Session，确认 v2 查不到它、旧键内容与 TTL 仍在，并在 finally 清理；JSON 往返断言增强为精确的新 domain 类名。原有 94 项保留，其他断言未削弱。
- package（跳过已完成测试）退出 0；JAR 包含全部新分层类、没有旧根包类，Mapper / type-handler / Session v2 配置正确；未启动独立 JAR JVM。依赖、数据库 schema / migration、Cookie 与密码计算容量未变。
- 相对本轮源码快照，排除 package / import / 空行后，所有既有 production 方法正文完全一致；只有上述两个测试的正文发生获准变化。唯一持久化 / 会话行为变化为用户确认的新 principal 类型与 namespace v2；不能笼统称为全部行为不变。
- 本轮使用一次性 PostgreSQL 18.6 / Redis 7.2.16，loopback 动态端口、tmpfs、独立随机凭据、空 schema Flyway V1 与既有隔离标识，密码容量保持 1；全部运行前后源码快照一致。结束时 app_user / auth_identity / local_password_credential 各 6、app_access_grant 5（认证组合有意撤销一条），Flyway 成功 1、测试 trigger 0、Redis key 0；两个容器均已删除，无既有业务库 / 卷操作，无 FLUSHDB / FLUSHALL。
- 证据：忽略的 `services/identity/target/r1g-20261030-full-layout/` 内两批日志、JUnit XML、package.log 与 summary.json；迁移前快照 / 映射与编译日志在 `/tmp/dailylanguage-identity-layout-20261030/`。命令沿用前述 focused / regression / package 命令的测试类简单名；实际 XML 包名已对应新位置。
- Code 自查无阻塞问题，Scope MATCH；目录变更及 principal 迁移之外没有顺手重构。Flow、有效源码链接与本地运行说明已同步当前结构和 namespace v2。Codex 自查不是独立作者 Review，也不提高模块 Ownership。
- 本轮模块回归属于新目录 / namespace v2 的 fresh 证据；前一轮 17/17 与 77/77 保留原有日期和范围，不混写。旧 server、前端 / 浏览器、Hosted HTTPS、跨服务、生产容量与未约定的故障注入仍 NOT_RUN；不扩大为整个仓库 / 公网验收 PASS。

#### Human Review 继续 — 2026-10-30

- 用户在 logout 配置、当前会话失效 / 其他会话保留及 CSRF 导读后表示“logout 这部分看懂了”；记录为该段反馈，不据单句反馈认定整个 R1-G Human Review 完成或提高模块掌握等级。
- 认证身份与应用准入讨论中，用户追问 authorities 是否表示应用列表、未来校验结果的含义；解释后反馈“大概懂了，继续”。记录为初步学习反馈，不自动认定完整权限设计已掌握。当前 Provider 返回空 authorities，me 仅要求 authenticated；未来在线准入核验仍属对应 Slice，不新增授权实现。
- 继续导读已批准的 principal 类名迁移与 Session v2：隔离旧会话、避免按已移动的旧类名还原；重新登录建立 v2 会话，账户数据不变。实现、验证与关键调用链导读已覆盖，当前等待整批 Human Review 判断，不追加固定考试或自动认定 Ownership 完成。本轮仅同步进展，未重跑测试，沿用同日 PRIOR 18/18 与 77/77。

#### Session 兼容性理解复核 — 2026-10-31

- 用户在追踪 Redis Hash 属性、认证 JSON 的 @class 与实际 serializer 配置后，自行说明：保留 v1 时，新生成的会话仍会写入新类名；风险是读到迁移前已有旧会话并尝试按旧类名还原。该判断正确，用户随后表示“那我就懂了”；具体 evidence 已补入 LEARN-003。
- namespace 切换属于用户已批准的隔离策略，不是移动目录的必然要求，也不负责转换 JSON 类型；Codex 将早前“找到已有旧会话”误读为“key 随机冲突”的解释已更正。不将该误读归为用户知识错误，不据局部正确推理提高模块等级或自动收口整批 Review。
- 本轮未改 production / 测试、未重跑验证；18/18 与 77/77 两批及 package 均为 2026-10-30 PRIOR。

上述为 Review 过程中的待确认状态，用户整批结论见下方收口记录。

### Human Review 收口 — R1-G，2026-10-31

- 在当前 R1-G 实现、完整分层与 Session v2 取舍已导读和讨论后，用户对“是否接受整批实现与范围”明确回复“接受”。本次 scoped Human Review COMPLETE，不将其解释为 commit / push 授权或下一业务 Slice 的批准。
- Review evidence 包括：用户参考完整示范后写入 handler，追踪 204 / 响应提交 / 外层 Filter 与 writer 的真实调用关系；要求恢复并扩展项目分层；明确接受旧会话失效方案；自行正确说明 v1 下新会话仍写入新类名、风险来自读取已有旧会话。logout 已反馈理解，应用准入讨论保留初步理解的实际范围，不补造独立实现、生产操作或完整框架掌握。
- FRESH 仅限 Git / source 核对：分支 migrate/identity、HEAD c916addcd928159fd99dbfe1c49327af8bc0163c；当前 services/identity/src 全部文件与最终验收 summary.json 中的 SHA-256 / 文件集合一致。2026-10-30 的 HTTP / Session 18/18、其余 Identity 77/77 与 package PASS 均为 PRIOR，本轮未重跑。
- 无待修 blocking finding，Flow / 源码引用与当前结构一致。Codex 自查与用户 Review 分开记录，OWNERSHIP_MATRIX 等级不变；LEARN-003 保留具体 reasoning evidence，不自动改为整条 CONFIRMED / OPERATED。

Current Gate: READY_TO_COMMIT。Next Action：用户决定提交范围与时机；不自动 commit / push / merge，不开始下一业务 Slice。
