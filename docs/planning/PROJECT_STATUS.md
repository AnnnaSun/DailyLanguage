# AI Language Tutor — Project Status

> Last updated: 2026-10-21 — R1-A 验收通过；当前 REVIEW_PENDING，待完整导读与用户 Critical Review。
> 当前状态入口；详细合同在 Feature / Phase / ADR，早期历史见 [历史快照](history/PROJECT_STATUS_2026-10-10.md)。
> 用户已在提示后完成 Human Core；本轮集中验收当前候选，历史 C31 不计为 fresh 迁移验证，不自动提高 Ownership。

## Current Gate

| 项目 | 当前状态 |
|---|---|
| Product Phase | M1 — Minimum Text Practice Loop；尚未达到 S11 / S12 exit |
| S11 | PARTIAL；A observation API、B 最小认证 UX 已实现、用户 Review 接受并提交；其余 UI 未实现 |
| Current work | R1-A / REVIEW_PENDING — 用户事务方法完成，18 项集中测试、独立打包与真实 JVM 重启持久化检查 PASS；待完整导读与用户 Critical Review |
| C31 | PRIOR PATCHED_C31_PASS；一次性工程、临时补丁与运行产物已按用户要求删除；不证明官方原版兼容或 Identity 行为 |
| Git baseline | `architecture/microservices-migration` / `f5a729a`；本轮开始工作区干净；当前未提交修改以 Git 为准 |
| Main baseline | 本地 main、origin/main 与只读查询远端 main 均为 `f5a729a`，即 PR #28 合并提交 |
| Current scope | 用户批准 R1-A 账户原子持久化及默认 Language 授权、首次骨架预算例外与中文业务注释；事务方法由用户实现 |
| Next Action | 当前 R1-A 代码导读与用户 Critical Review；合同见 [Identity R1-A](../features/IDENTITY.md)，调用链见 [Identity 事务 Flow](../flow/identity-account-persistence.md) |

旧 PROJECT_STATUS 的 S10 READY_TO_COMMIT 与旧 CURRENT_HANDOFF 的 S9 Gate 已失效；不据此重开已完成的 Review。
CURRENT_HANDOFF 本轮未自动刷新，仍为旧 S9 快照；当前迁移入口为 [Identity R1-A](../features/IDENTITY.md)。
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
| 平台迁移 / R1-A | 完整 ADR 仍 DESIGN_REVIEW；首个账户 slice 验收 PASS / REVIEW_PENDING；用户 Human Core 为提示后实现，未切换业务消费者，Ownership 未自动提升 | [Identity R1-A](../features/IDENTITY.md)、[ADR-0005](../adr/0005-unified-learning-platform-microservices.md)、[C31 摘要](../architecture/PLATFORM_ARCHITECTURE_PROPOSAL.md#93-c31-证据摘要与当前边界--2026-10-21) |

S11-A 的 Human Core 曾由用户明确转交 Codex；S11-B / C31 由 Codex 实现。不记作用户 hands-on，
Review 接受与 commit 不自动提高模块掌握等级。[Ownership Matrix](../ownership/OWNERSHIP_MATRIX.md) 保持既有等级。

## Active Decisions / 迁移与恢复边界

- 迁移前分支已通过 PR #28 合并；本轮在迁移分支实施已批准的 R1-A 骨架，不继续 S11 或开始 S12，不 commit / push。
- 已确认 C25：共同版本／基础合同 → Identity → PersonalNote 最小只读认证接入 → Language 身份／本地引用切换 → 扩大迁移。各步仍需小 slice 合同；完整设计、命名、身份接口和数据操作未整体获批。
- C28 首选 Java 25 / Boot 4.1.1 / Cloud 2025.1.3；新 Identity 使用该基线、仅导入 Cloud BOM，旧 server 仍 Boot 4.1.0 且尚未接入新平台。SAA 与完整生产组合未验证。
- C31 补丁授权与运行保留为历史；工程及补丁已删除，不恢复。未来如确需补丁，在具体合同说明用途、影响和替代方案，由用户决定。mTLS 不替代用户／资源授权。
- 统一 Vue 前端按 C26 计划放入独立仓库，尚未迁出。恢复 S11 前重新核对入口、API、Cookie / CSRF、languageProfileId、Provider / Credential 合同，不直接沿用单体地址和部署假设。
- 语言隔离、Java deterministic / persistence authority、后端临时使用 Credential 且不落库／日志／Trace 不变。C19 设备持久保存属于未来方向，保护方案未定；S11 当前没有模型 Key 存储实现。
- Guided Language Learning、M2C 与后续 Phase 的既有批准保留；本次不扩大到 kana 教学、开放对话、新评分引擎、live DeepSeek、新业务 dependency 或完整学习体系。

## Verification / fresh、prior 与 NOT_RUN

| 日期 / 性质 | 范围与结果 | 局限 |
|---|---|---|
| 2026-10-11 / PRIOR | S11-A unit 88/88、独立 PostgreSQL / Redis integration 43/43 PASS，0 skipped | 非浏览器、非 live Provider |
| 2026-10-13 / PRIOR | S11-B client 16/16、Vue type/build、auth HTTP 27/27、Redis Session 2/2、本地 browser 路径 PASS | 当时匿名 CSRF 403 断言失败，后续根因／修复见下一行；非 Hosted HTTPS |
| 2026-10-15 / PRIOR | CSRF 修复后真实 Tomcat HTTP 14 + 既有 HTTP 30，共 44/44 PASS，0 skipped | 修复后未重跑 browser / PostgreSQL / Redis 全链路；失败历史保留在 Feature |
| 2026-10-20 / PRIOR，2026-10-21 读取证据 | C31 补丁版运行 20261020T151253Z-6e7823：17 项 PASS；保存脚本与当前源码一致 | 原版对照 PASS 表示预期 TLS 失败已复现，不算官方兼容通过 |
| 2026-10-21 / PRIOR | C31 断言 unit 9/9 PASS；核对 summary、补丁 hash、源码快照与清理记录；97 个本地文档路径可解析、两个新 checkpoint anchor 与 diff whitespace 检查 PASS | 不重新证明真实 RPC / TLS；两个 PersonalNote 引用为本机相邻项目路径，不承诺独立 clone 可用 |
| 2026-10-21 / FRESH | 当前 Identity 候选 HTTP 9 + Mapper / schema / 账号边界 5 + 用户事务 4，共 18/18 PASS，0 skipped；独立 package PASS；同一 JAR 两个 JVM 启动健康，四表逐行摘要跨启动保持一致 | 验收限定内部账户持久化，非注册 / Session / 跨服务验收；完整证据与历史 RED 见 [Identity R1-A](../features/IDENTITY.md#verification) |
| 本轮 / NOT_RUN | 完整注册 / 密码验证 / Session、旧 Java / Vue / 浏览器回归、跨服务认证、真实 C31 重跑、live Provider、SAA、K3s / Tekton、生产部署、数据库故障恢复和提交确认丢失 | 应用重启不等于数据库崩溃恢复；不以当前内部持久化或历史结果代替后续业务迁移回归 |

完整 S11 批次、失败与局限见 Feature §18–19；C31 仅保留平台方案 §9.3 的脱敏摘要。
C31 一次性验证源码及本机诊断日志／构建产物已按用户 2026-10-21 的要求删除；
仅保留上述脱敏摘要，不保留临时补丁、测试凭据或运行产物；当前业务代码未采用该补丁。

## Outstanding Gates

- S11-A/B 无待补 Review；C31 只保留脱敏历史证据，不要求恢复已删除代码或补丁，也不将它记作用户 hands-on / Ownership PASS。
- R1-A 内部持久化验收 PASS，当前 REVIEW_PENDING，待完整导读与用户 Critical Review；不凭测试通过进入 READY_TO_COMMIT 或提高 Ownership。
- 平台完整 ADR、跨服务认证／故障、前端迁出与仓库命名仍待后续决策；本轮没有批准这些后续实现。
- S11 整体 PARTIAL，迁移后重新定义下一 slice；没有 S11 全页面端到端验收或 S12 closeout PASS。
- S9 process-recovery、model result unknown、长期 qualification / Memory / Mastery、Hosted 容量与 TLS 的既有局限保留。测试不证明真实日语诊断质量、学习有效性或用户新掌握等级。
