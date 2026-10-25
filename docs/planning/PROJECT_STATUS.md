# AI Language Tutor — Project Status

> Last updated: 2026-10-26 — R1-F READY_TO_COMMIT；工程验收 / 自查 PASS，Session Flow CURRENT，用户已接受本 Slice Human Review；80/80、数据库 10/10 两批及 package 为 2026-10-25 PRIOR。等待用户 Commit Decision，Codex 不自动提交。
> 当前状态入口；详细合同在 Feature / Phase / ADR，早期历史见 [历史快照](history/PROJECT_STATUS_2026-10-10.md)。
> R1-A / R1-B / R1-C Human Core 均为用户提示后实现；当前验证不代表公开注册 / 登录 / Session 或跨服务验收通过，也不自动提高模块整体 Ownership。

## Current Gate

| 项目 | 当前状态 |
|---|---|
| Product Phase | M1 — Minimum Text Practice Loop；尚未达到 S11 / S12 exit |
| S11 | PARTIAL；A observation API、B 最小认证 UX 已实现、用户 Review 接受并提交；其余 UI 未实现 |
| Current work | R1-F READY_TO_COMMIT；Session 存储基础工程验收 / 自查 PASS，Flow CURRENT，Human Review COMPLETE；Codex 实现，不记用户 hands-on，尚未提交 |
| C31 | PRIOR PATCHED_C31_PASS；一次性工程、临时补丁与运行产物已按用户要求删除；不证明官方原版兼容或 Identity 行为 |
| Git baseline | 2026-10-25 核对：`migrate/identity` / `8630e6fddefaad53acc01dc721c757705a17c1a5`（email登录认证）；提交包含 R1-E 的 12 个实现 / 测试 / 文档文件，同步前工作区干净，Provider 与收口 blob 3fc6e77 一致 |
| Main baseline | 迁移启动时核对的本地 main、origin/main 与远端 main 均为 `f5a729a`，即 PR #28 合并提交；本次未重新查询远端 |
| Current scope | 已批准 R1-F：Boot-managed Session Redis、精确 JSON 类型许可、独立 Redis / namespace / TTL / Cookie 配置与代表性存取 / 故障测试；Codex 完整实现，不接通公开认证入口 |
| Next Action | 用户决定提交当前 [R1-F](../features/IDENTITY.md#r1-f--redis-session-基础) 实现、测试与相关文档；Codex 不自动 commit / push 或开始下一 Slice |

旧 PROJECT_STATUS 的 S10 READY_TO_COMMIT 与已删除交接快照的 S9 Gate 均为历史状态，不据此重开已完成的 Review。
当前迁移入口为 [Identity R1-F](../features/IDENTITY.md#r1-f--redis-session-基础)；恢复顺序为 AGENTS → 本文件 → 当前 Feature / Slice 合同，再核对 Git 与源码。
S11 恢复入口保留在 [S11 checkpoint](../features/M1_MINIMUM_TEXT_PRACTICE.md#20-s11-migration-checkpoint--2026-10-21)。

## Delivery Summary

| 范围 | 已确认状态 | 详细来源 |
|---|---|---|
| M0、M1-S1–S8T | 既有实现、验证与 Review / Ownership 记录保留；S8T merged `c2dbb4f` | [Phase Plan](V1_PHASE_PLAN.md)、[Flows](../flow/README.md) |
| M1-S9 | COMPLETE；full-feature Ownership 已完成，提交 `d68d70f`…`786f124` | [Planner Enrichment](../features/PLANNER_ENRICHMENT.md) |
| M1-S10 | COMPLETE；scoped User Review 已接受；资源、测试与规则／文档一起提交 `badcf6e` | [M1 Feature §7.13](../features/M1_MINIMUM_TEXT_PRACTICE.md#713-implemented-m1-s10-boundary) |
| M1-S11-A | COMPLETE / COMMITTED `d7aa32e`；校验并保存首次 response support snapshot | [M1 Feature §18](../features/M1_MINIMUM_TEXT_PRACTICE.md#18-m1-s11-a-current-slice-contract--implementation) |
| M1-S11-B | COMPLETE / COMMITTED `958ac95`；最小 Vue 认证、API client、CSRF ERROR-dispatch 修复；`a486ef7` 另补 Phase Plan，无代码修改 | [M1 Feature §19](../features/M1_MINIMUM_TEXT_PRACTICE.md#19-m1-s11-b-current-slice-contract--implementation)、[Browser Flow](../flow/browser-authentication.md) |
| S11 剩余 / S12 | Language Profile、task、guided practice、response/support capture、completion、结果与 optional evaluation 的页面闭环尚未实现；后续 slice 合同未批准；S12 未开始 | [S11 checkpoint](../features/M1_MINIMUM_TEXT_PRACTICE.md#20-s11-migration-checkpoint--2026-10-21) |
| 平台迁移 / R1-A | 首个账户 slice 验收 PASS / COMMITTED `9b3ef1a`；用户在代码导读与 Review 讨论后自主提交，Human Core 为提示后实现；完整 ADR 仍 DESIGN_REVIEW，业务消费者未切换，Ownership 未自动提升 | [Identity R1-A](../features/IDENTITY.md)、[ADR-0005](../adr/0005-unified-learning-platform-microservices.md)、[C31 摘要](../architecture/PLATFORM_ARCHITECTURE_PROPOSAL.md#93-c31-证据摘要与当前边界--2026-10-21) |
| 平台迁移 / R1-B | COMPLETE / COMMITTED `3f0d36f`；工程验收 PASS、自查无阻塞问题、关键路径 Ownership Check 完成；不自动提高模块整体掌握等级 | [Identity R1-B](../features/IDENTITY.md#r1-b--受限密码散列与校验)、[密码 Flow](../flow/identity-password-hashing.md) |
| 平台迁移 / R1-C | COMPLETE / COMMITTED `1738792`；工程验收 PASS、Code Review 无阻塞问题、Human Review 完成；Human Core 提示后实现 | [Identity R1-C](../features/IDENTITY.md#r1-c--注册密码策略与离线-blocklist) |
| 平台迁移 / R1-D | COMPLETE / COMMITTED `c1be36d`；工程验收 PASS，Flow CURRENT，Human Review COMPLETE；Human Core 保持提示 / 局部示例后实现，不提升模块整体 Ownership | [Identity R1-D](../features/IDENTITY.md#r1-d--identity-内部-local_email-注册闭环) |
| 平台迁移 / R1-E | COMPLETE / COMMITTED `8630e6f`；工程验收、Flow 与 Human Review 已收口，Human Core 提示 / 局部示例后实现，不提升模块 Ownership | [Identity R1-E](../features/IDENTITY.md#r1-e--内部-local_email-凭据认证) |
| 平台迁移 / R1-F | READY_TO_COMMIT；工程验收 / 自查 PASS，Flow CURRENT，Human Review COMPLETE；80/80、数据库 10/10 两批及 package 为 PRIOR，Codex 实现，不提升模块 Ownership | [Identity R1-F](../features/IDENTITY.md#r1-f--redis-session-基础) |

S11-A 的 Human Core 曾由用户明确转交 Codex；S11-B / C31 由 Codex 实现。不记作用户 hands-on，
Review 接受与 commit 不自动提高模块掌握等级。[Ownership Matrix](../ownership/OWNERSHIP_MATRIX.md) 保持既有等级。

## Active Decisions / 迁移与恢复边界

- 迁移前分支已通过 PR #28 合并，R1-A / R1-B / R1-C 已纳入 PR #29；R1-D 已提交 c1be36d，R1-E 已提交 8630e6f。2026-10-25 用户批准 R1-F 范围、160–220 行测试预算和 Codex 完整实现；不自动 commit / push。
- R1-B 仅在 Identity 增加受限密码散列 / 校验和 bcprov-jdk18on:1.86；进程限额本地默认 1，散列与校验共享，容量不足立即拒绝。用户 Human Core 不由 Codex 代写，旧 server 及其依赖保持原样。
- R1-C 只迁入既有注册密码 policy 与固定 blocklist，用户实现 validate；helper / 资源 / 测试由 Codex 准备。原始密码不改写，保持 12–64 printable ASCII 与 exact matching；不进入现有 matches 登录校验链，不启用注册 / Session，不新增 dependency 或数据库行为。
- 已确认 C25：共同版本／基础合同 → Identity → PersonalNote 最小只读认证接入 → Language 身份／本地引用切换 → 扩大迁移。各步仍需小 slice 合同；完整设计、命名、身份接口和数据操作未整体获批。
- C28 首选 Java 25 / Boot 4.1.1 / Cloud 2025.1.3；新 Identity 使用该基线、仅导入 Cloud BOM，旧 server 仍 Boot 4.1.0 且尚未接入新平台。SAA 与完整生产组合未验证。
- C31 补丁授权与运行保留为历史；工程及补丁已删除，不恢复。未来如确需补丁，在具体合同说明用途、影响和替代方案，由用户决定。mTLS 不替代用户／资源授权。
- 统一 Vue 前端按 C26 计划放入独立仓库，尚未迁出。恢复 S11 前重新核对入口、API、Cookie / CSRF、languageProfileId、Provider / Credential 合同，不直接沿用单体地址和部署假设。
- 语言隔离、Java deterministic / persistence authority、后端临时使用 Credential 且不落库／日志／Trace 不变。C19 设备持久保存属于未来方向，保护方案未定；S11 当前没有模型 Key 存储实现。
- Guided Language Learning、M2C 与后续 Phase 的既有批准保留；本次不扩大到 kana 教学、开放对话、新评分引擎、live DeepSeek、新业务 dependency 或完整学习体系。

## Verification / fresh、prior 与 NOT_RUN

下列历史行内 FRESH 仅指原验证批次。2026-10-26 Review 收口只核对 Git / source 并同步记录，未重跑测试；R1-F 的 80/80、数据库 10/10 两批及 package 为 2026-10-25 PRIOR，不混写为本轮 fresh 或公开认证验收。

| 日期 / 性质 | 范围与结果 | 局限 |
|---|---|---|
| 2026-10-11 / PRIOR | S11-A unit 88/88、独立 PostgreSQL / Redis integration 43/43 PASS，0 skipped | 非浏览器、非 live Provider |
| 2026-10-13 / PRIOR | S11-B client 16/16、Vue type/build、auth HTTP 27/27、Redis Session 2/2、本地 browser 路径 PASS | 当时匿名 CSRF 403 断言失败，后续根因／修复见下一行；非 Hosted HTTPS |
| 2026-10-15 / PRIOR | CSRF 修复后真实 Tomcat HTTP 14 + 既有 HTTP 30，共 44/44 PASS，0 skipped | 修复后未重跑 browser / PostgreSQL / Redis 全链路；失败历史保留在 Feature |
| 2026-10-20 / PRIOR，2026-10-21 读取证据 | C31 补丁版运行 20261020T151253Z-6e7823：17 项 PASS；保存脚本与当前源码一致 | 原版对照 PASS 表示预期 TLS 失败已复现，不算官方兼容通过 |
| 2026-10-21 / PRIOR | C31 断言 unit 9/9 PASS；核对 summary、补丁 hash、源码快照与清理记录；97 个本地文档路径可解析、两个新 checkpoint anchor 与 diff whitespace 检查 PASS | 不重新证明真实 RPC / TLS；两个 PersonalNote 引用为本机相邻项目路径，不承诺独立 clone 可用 |
| 2026-10-21 / PRIOR | 已提交 R1-A 候选 HTTP 9 + Mapper / schema / 账号边界 5 + 用户事务 4，共 18/18 PASS，0 skipped；独立 package PASS；同一 JAR 两个 JVM 启动健康，四表逐行摘要跨启动保持一致 | 验收限定 R1-A 内部账户持久化，不代替 R1-B 验收或注册 / Session / 跨服务验收；完整证据与历史 RED 见 [Identity R1-A](../features/IDENTITY.md#verification) |
| 2026-10-21 / 记录同步 | 核对用户提交 `9b3ef1a`、23 个文件及同步前干净工作区；仅修改 Status 与 Identity 合同 | 本次未重跑构建或测试；沿用上述有日期的验收证据，不新增运行验证结论 |
| 2026-10-22 / PRIOR | R1-B 密码组件 17/17、HTTP 边界 9/9 PASS；真实并发 / 配置测试 12 项中 7 PASS、4 failures、1 error，均无 skipped；集中命令实际退出码 1 | 5 项为 Human Core 占位的预期 RED；密码测试的容量 mock 不证明真实并发。新数据库集成与 R1-A 数据库回归本轮 NOT_RUN；当前不是整体验收 PASS |
| 2026-10-24 / FRESH | R1-B 补回依赖前 38 项中 35 PASS、3 errors，均为缺少 Bouncy Castle 类；补回 `bcprov-jdk18on:1.86` 后密码组件 17/17、真实并发 / 配置 12/12、HTTP 边界 9/9，共 38/38 PASS，0 skipped，退出码 0 | 默认权限下 Mockito attach 遇 Windows 管道访问拒绝，终止后在获准的正常权限环境完成重跑；未改 Human Core。数据库组合、独立 package / 完整服务启动仍 NOT_RUN |
| 2026-10-24 / FRESH，剩余验收 | 已提交 `3f0d36f`：独立 PostgreSQL 18.6、空 schema Flyway V1；真实 verifier 组合 1/1、Mapper / schema / 账号边界 5/5、账户事务 4/4，共 10/10 PASS，0 skipped；package 退出码 0，独立启动 JAR 健康 UP；临时 JVM / tmpfs 数据库已清理 | 先前 38 项为同日上一批证据，本次未重复；未重做两次启动逐行摘要比较，不证明生产容量、完整认证或跨服务行为。详细版本、命令与证据见 Identity 合同 |
| 2026-10-24 / FRESH，R1-C 骨架 | blocklist 12/12（含资源损坏拒绝和真实 Spring bean 创建）、既有密码 / 容量 / HTTP 38/38 PASS；policy 8 项因占位 RED（1 failure、7 errors），总计 58 项、50 PASS、0 skipped，实际退出码 1 | 不代表 validate 已可用或整体验收通过；上游重新生成、完整 JAR / PostgreSQL / browser / Session / 跨服务本批 NOT_RUN |
| 2026-10-24 / FRESH，R1-C Human Core Review | policy 8/8、blocklist 12/12、既有密码 / 容量 / HTTP 38/38，共 58/58 PASS，0 skipped；package 退出码 0；产出 JAR 内 blocklist 66,272 bytes，SHA-256 与固定值一致 | Codex 未修改用户实现；真实组件创建由 blocklist 测试覆盖，未另启动完整服务或 PostgreSQL，不证明公开注册 / 登录 / Session / 跨服务行为 |
| 2026-10-25 / PRIOR，R1-D 骨架，精简前 | 全部 test sources 编译成功；normalizer 25/25、既有 policy / blocklist / hasher / capacity / HTTP 58/58 PASS；Service 14 项因 Human Core 占位 RED（13 failures、1 error）；总计 97 项、83 PASS、0 skipped，退出码 1 | 默认权限 Mockito 附加受阻后已停止，在获准正常权限环境重跑；未改配置。helper 的完整调用与数据库链尚未证明，不是 Slice 验收 PASS |
| 2026-10-25 / PRIOR，Identity 测试去重 | normalizer 8、policy 7、blocklist 8、hasher 14、capacity 8，共 45/45 PASS；Service 11 项占位 RED（10 failures、1 error）；总计 56 项、0 skipped，退出码 1；测试源码编译成功，production hash 不变，diff whitespace PASS | 只删重复覆盖和精简输入排列，保留真实并发 / 回滚 / 资源完整性；数据库及 HTTP 本批未运行，Human Core 当时尚未实现；详见 Identity 的测试去重记录 |
| 2026-10-25 / PRIOR，register 增量 Review，修正前 | 仅 Service tests 11 项：10 PASS、1 failure、0 errors / skipped，退出码 1；异常捕获类型不符导致 DataAccessResourceFailureException 直接传播 | 当时交回用户修正；Codex 未改用户代码。其余组件、数据库 / HTTP / package 本批 NOT_RUN |
| 2026-10-25 / PRIOR，register 修正后 | 用户修正 RuntimeException 捕获与安全 PERSISTENCE 日志；既有 Service tests 11/11 PASS、0 failures / errors / skipped，退出码 0；diff whitespace PASS | Codex 未改用户 register；其他组件 / HTTP / 数据库 / package 当批 NOT_RUN。定向通过不代表完整工程验收或用户 Ownership 完成 |
| 2026-10-25 / PRIOR，整体 Review | normalizer 8、Service 11、policy 7、blocklist 8、hasher 14、capacity 8、HTTP 9，共 65/65 PASS、0 failures / errors / skipped；package 与 diff whitespace PASS，退出码 0 | 当时新 / 既有数据库 9 项未执行、注册 Flow 缺失；两项缺口已由下一批补齐；无实现或测试修改 |
| 2026-10-25 / FRESH，数据库与 Flow 修正 | PostgreSQL 18.6 一次性隔离库，空 schema Flyway V1；新注册组合 1、账户事务 4、Mapper 3、密码数据库组合 1，共 9/9 PASS、0 failures / errors / skipped，package 退出码 0；Flow 已补齐 | 保持原容量 1 与既有测试，未修改 Human Core；4 个完整合成账户、测试 trigger 遗留 0；临时容器 / tmpfs 已清理，不证明生产容量、公开认证或提交确认丢失 |
| 2026-10-25 / FRESH，R1-E 骨架 | 全部测试源码编译成功；既有组件 / Service / HTTP 65/65 PASS，新 Provider 11 项中 5 PASS、5 failures、1 error，均为 Human Core 占位 RED；共 76 项、70 PASS、0 skipped，退出码 1；diff whitespace PASS | 默认权限 Mockito 附加受阻后终止，在获准正常权限环境重跑，未改测试配置；数据库组合仅编译未执行、package 未运行，不是 Slice 验收 PASS |
| 2026-10-25 / FRESH，R1-E Human Core 增量 Review | Provider 11 项：8 PASS、3 failures、0 errors / skipped，退出码 1；三项均为凭据拒绝异常类型不符；diff whitespace PASS | Codex 未改用户实现或测试；用户待将 AuthenticationServiceException 改为 BadCredentialsException，仅限凭据拒绝分支；其余回归 / 数据库 / package 本批 NOT_RUN |
| 2026-10-25 / FRESH，R1-E 异常分类修正 | 用户改用 BadCredentialsException；Provider 11/11 PASS、0 failures / errors / skipped，退出码 0；diff whitespace PASS | Codex 未修改用户 production / 测试；仅增量验证，数据库 / package / 整体工程验收与认证 Flow 本批未完成，不自动提高 Ownership |
| 2026-10-25 / FRESH，R1-E 整体工程验收 | 第一批 Provider / 组件 / Service / HTTP 76/76 PASS；第二批 PostgreSQL 18.6 空 schema Flyway V1，新 / 既有组合与账户回归 10/10 PASS；均 0 failures / errors / skipped、package 退出码 0；认证 Flow CURRENT | 两批独立证据，密码容量保持 1；真实认证未恢复撤销的准入；Provider blob 未变，未修改用户核心段；临时容器 / tmpfs 已清理，不证明公开登录 / Session / 生产 / 独立 JAR 或用户 Ownership |
| 2026-10-25 / R1-E Human Review 收口 | 用户实际查看代码后接受当前判断，无待修问题；当前 Provider 3fc6e77 与验收版 2476e0a 的 blob diff 仅为删除骨架说明，逻辑不变；diff whitespace PASS | 本轮未重跑测试，76/76 与数据库 10/10 为同日 PRIOR；Human Core 保持提示 / 局部示例后实现，不升级模块 Ownership，不自动提交 |
| 2026-10-25 / FRESH，R1-F | 第一批 JSON 2、真实 Redis 1、不可用 Redis 1、既有 Identity 76，共 80/80 PASS；第二批 PostgreSQL 账户 / 认证组合 10/10 PASS；均 0 failures / errors / skipped、package 退出 0；JAR、Compose 与 diff whitespace 检查 PASS，Session Flow CURRENT | 初始 starter 缓存缺失与本机权限失败保留在 Feature；真实 TTL / Cookie 配置绑定不证明浏览器会话生命周期；Redis 测试键 0，两临时容器 / tmpfs 已清理，R1-E 核心未改；不自动提高 Ownership |
| 2026-10-26 / R1-F Human Review 收口 | 用户在分工和真实 Slice 边界讨论后接受当前实现；关键 source blob 与验收记录一致，diff whitespace PASS | 未重跑测试或修改 production / 测试；沿用 2026-10-25 PRIOR，不记用户 hands-on 或独立掌握，不自动提交 |
| 本轮 / NOT_RUN | 独立 JAR JVM / 双启动摘要、JAR blocklist 单独完整性校验、公开注册 / 登录 / Session、旧 Java / Vue / 浏览器回归、跨服务认证、真实 C31 重跑、live Provider、SAA、K3s / Tekton、生产部署、数据库崩溃恢复和提交确认丢失 | 当前通过限于 Identity 内部注册及既有账户回归；不以测试代替公开认证、生产或用户 Ownership 结论 |

完整 S11 批次、失败与局限见 Feature §18–19；C31 仅保留平台方案 §9.3 的脱敏摘要。
C31 一次性验证源码及本机诊断日志／构建产物已按用户 2026-10-21 的要求删除；
仅保留上述脱敏摘要，不保留临时补丁、测试凭据或运行产物；当前业务代码未采用该补丁。

## Outstanding Gates

- S11-A/B 无待补 Review；C31 只保留脱敏历史证据，不要求恢复已删除代码或补丁，也不将它记作用户 hands-on / Ownership PASS。
- R1-A 内部持久化验收 PASS，用户在代码导读与 Review 讨论后已本地提交 `9b3ef1a`，当前 COMMITTED；不据此提高 Ownership 或自动开始下一 slice。
- R1-B COMPLETE / COMMITTED `3f0d36f`，收口文档已提交 `0d69221`：工程验收、自查、密码 Flow 与关键路径 Ownership Check 完成；Human Core 保持提示后实现记录，不提高模块整体掌握等级。
- R1-C COMPLETE / COMMITTED `1738792`；工程验收 PASS，无 blocking finding；用户的实际实现 / 修正、对邮箱调用边界的质疑与保留当前写法的设计判断构成本 slice 的 Human Review evidence。Human Core 保持提示后实现，不提升模块整体掌握等级，不重开 Review。
- R1-D COMPLETE / COMMITTED `c1be36d`；65/65 与数据库 9/9 两批、package / Flow / Human Review 已收口，提示 / 局部示例后实现记录保留，不自动提高模块 Ownership。
- R1-E COMPLETE / COMMITTED `8630e6f`；工程验收、自查、认证 Flow 与本 Slice Human Review 已收口，不重新打开，无公开登录 / 跨服务验收结论，不提升模块 Ownership。
- R1-F READY_TO_COMMIT；工程验收 / 自查、Session Flow 与本 Slice Human Review 已收口，等待用户 Commit Decision；Codex 实现，不记用户 hands-on 或升级模块 Ownership，不自动提交或开始下一 Slice。生产登录写入 Session / rotation / logout 与跨服务核验仍未实现。
- 2026-10-24 已按用户要求在 Identity POM 补回 `bcprov-jdk18on:1.86`，解决实际复现的 3 项缺类错误；此前声明缺失来源仍 UNKNOWN，未据此确认 Maven / IDE 根因。
- 平台完整 ADR、跨服务认证／故障、前端迁出与仓库命名仍待后续决策；本轮没有批准这些后续实现。
- S11 整体 PARTIAL，迁移后重新定义下一 slice；没有 S11 全页面端到端验收或 S12 closeout PASS。
- S9 process-recovery、model result unknown、长期 qualification / Memory / Mastery、Hosted 容量与 TLS 的既有局限保留。测试不证明真实日语诊断质量、学习有效性或用户新掌握等级。
