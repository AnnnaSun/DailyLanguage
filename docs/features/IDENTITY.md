# R1 Identity — Slice 合同与 Human Core

## 当前 Gate

- Date: 2026-10-24
- Current Slice: R1-B — Identity 内部的受限密码散列与校验。
- Gate: REVIEW_PENDING；用户已提示后实现并发控制方法，补回 `bcprov-jdk18on:1.86` 后定向测试 38/38 PASS。
- R1-A 已提交 `9b3ef1a`；记录同步已提交 `2dda5de`，本 slice 从 `architecture/microservices-migration` / `2dda5de` 开始。
- Human Core 逻辑 Review 无阻塞问题；数据库组合验证、整体 Review 与用户 Ownership 待完成。详细合同见下方 R1-B。
- 不自动 commit / push，不提高 Ownership，不开始下一 slice。

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

入口：[PasswordHashConcurrencyGate.runWithAvailableSlot](../../services/identity/src/main/java/com/dailylanguage/identity/credential/PasswordHashConcurrencyGate.java)。
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

Acceptance: NOT_COMPLETE。当前 REVIEW_PENDING；单一 Next Action：继续隔离 PostgreSQL 组合验证及受影响的 R1-A 数据库回归，随后整体 Review；不自动 commit 或开始下一 slice。
