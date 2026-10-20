# AI Language Tutor — Project Status

> Last updated: 2026-10-21 — 迁移前收口；S11-A/B 已提交，S11 整体 PARTIAL，先进行平台迁移准备。
> 当前状态入口；详细合同在 Feature / Phase / ADR，早期历史见 [历史快照](history/PROJECT_STATUS_2026-10-10.md)。
> 本轮同步文档、核对证据与 Review，并运行 C31 的 9 个断言测试；历史运行结果不计为 fresh 验证。

## Current Gate

| 项目 | 当前状态 |
|---|---|
| Product Phase | M1 — Minimum Text Practice Loop；尚未达到 S11 / S12 exit |
| S11 | PARTIAL；A observation API、B 最小认证 UX 已实现、用户 Review 接受并提交；其余 UI 未实现 |
| Current work | DOCS_RECONCILED — 平台迁移前文档收口完成；业务迁移 implementation 尚未开始 |
| C31 | PATCHED_C31_PASS；验证工程代码按用户决定不提交，文档仅保留迁移证据；不等于官方原版兼容或生产批准 |
| Git baseline | `feature/m1s11-VuePracticeUX` / `a486ef7`（2026-10-21 核对；后续重新核对 Git） |
| Main baseline | 本地 `main` / 本地记录 `origin/main` 为 `c2dbb4f`；当前分支包含其后 17 个提交，包括 S9、S10、S11-A/B；未联网刷新远端 |
| Closeout scope | 13 个项目文档（含 AGENTS 与既有平台草案／规则）；仅文档提交，验证工程代码排除；无本轮业务代码变更。实时 HEAD / worktree 以 Git 为准 |
| Next Action | 用户后续决定 merge / 新分支时机；迁移首个实施合同仍需独立批准，不开始 Identity 或 S11-C |

旧 PROJECT_STATUS 的 S10 READY_TO_COMMIT 与旧 CURRENT_HANDOFF 的 S9 Gate 已失效；不据此重开已完成的 Review。
CURRENT_HANDOFF 本轮未自动刷新，仍为旧 S9 快照；本次正式恢复记录由本文件与 Feature §20 持有。
恢复入口为 [S11 checkpoint](../features/M1_MINIMUM_TEXT_PRACTICE.md#20-s11-migration-checkpoint--2026-10-21)。

## Delivery Summary

| 范围 | 已确认状态 | 详细来源 |
|---|---|---|
| M0、M1-S1–S8T | 既有实现、验证与 Review / Ownership 记录保留；S8T merged `c2dbb4f` | [Phase Plan](V1_PHASE_PLAN.md)、[Flows](../flow/README.md) |
| M1-S9 | COMPLETE；full-feature Ownership 已完成，提交 `d68d70f`…`786f124` | [Planner Enrichment](../features/PLANNER_ENRICHMENT.md) |
| M1-S10 | COMPLETE；scoped User Review 已接受；资源、测试与规则／文档一起提交 `badcf6e` | [M1 Feature §7.13](../features/M1_MINIMUM_TEXT_PRACTICE.md#713-implemented-m1-s10-boundary) |
| M1-S11-A | COMPLETE / COMMITTED `d7aa32e`；校验并保存首次 response support snapshot | [M1 Feature §18](../features/M1_MINIMUM_TEXT_PRACTICE.md#18-m1-s11-a-current-slice-contract--implementation) |
| M1-S11-B | COMPLETE / COMMITTED `958ac95`；最小 Vue 认证、API client、CSRF ERROR-dispatch 修复；`a486ef7` 另补 Phase Plan，无代码修改 | [M1 Feature §19](../features/M1_MINIMUM_TEXT_PRACTICE.md#19-m1-s11-b-current-slice-contract--implementation)、[Browser Flow](../flow/browser-authentication.md) |
| S11 剩余 / S12 | Language Profile、task、guided practice、response/support capture、completion、结果与 optional evaluation 的页面闭环尚未实现；后续 slice 合同未批准；S12 未开始 | [S11 checkpoint](../features/M1_MINIMUM_TEXT_PRACTICE.md#20-s11-migration-checkpoint--2026-10-21) |
| 平台迁移 / C31 | 方向部分确认，完整 ADR 仍 DESIGN_REVIEW；仅独立 RPC 验证已执行，业务服务尚未迁移 | [ADR-0005](../adr/0005-unified-learning-platform-microservices.md)、[C31 摘要](../architecture/PLATFORM_ARCHITECTURE_PROPOSAL.md#93-c31-证据摘要与当前边界--2026-10-21) |

S11-A 的 Human Core 曾由用户明确转交 Codex；S11-B / C31 由 Codex 实现。不记作用户 hands-on，
Review 接受与 commit 不自动提高模块掌握等级。[Ownership Matrix](../ownership/OWNERSHIP_MATRIX.md) 保持既有等级。

## Active Decisions / 迁移与恢复边界

- 用户本轮要求先收口，后续明确暂不 merge / 开新分支；验证工程代码不提交，原样保留本机文件。当前不继续 S11 或开始 S12。
- 已确认 C25：共同版本／基础合同 → Identity → PersonalNote 最小只读认证接入 → Language 身份／本地引用切换 → 扩大迁移。各步仍需小 slice 合同；完整设计、命名、身份接口和数据操作未整体获批。
- C28 首选 Java 25 / Boot 4.1.1 / Cloud 2025.1.3；当前业务 server 仍为 Boot 4.1.0。C31 为独立工程，未升级业务应用；SAA 与完整生产组合未验证。
- 已允许现有 Dubbo peer host 补丁用于本地学习迁移；不批准生产采用、长期 fork 或其他补丁。mTLS 不替代用户／资源授权，C31 不证明 Identity、Session 或跨服务权限。
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
| 2026-10-21 / FRESH | C31 断言 unit 9/9 PASS；核对 summary、补丁 hash、源码快照与清理记录；97 个本地文档路径可解析、两个新 checkpoint anchor 与 diff whitespace 检查 PASS | 不重新证明真实 RPC / TLS；两个 PersonalNote 引用为本机相邻项目路径，不承诺独立 clone 可用 |
| 本轮 / NOT_RUN | Java / Vue / 数据库／浏览器回归、真实 C31 网络重跑、live Provider、Identity、SAA、K3s / Tekton、生产部署 | 文档同步不是运行时验证 |

完整 S11 批次、失败与局限见 Feature §18–19；C31 仅保留平台方案 §9.3 的脱敏摘要。
C31 一次性验证源码及本机诊断日志／构建产物已按用户 2026-10-21 的要求删除；
仅保留上述脱敏摘要及 [原 Dubbo 补丁](../architecture/patches/dubbo-3.3.6-peerhost.patch)，不保留测试凭据或运行产物。

## Outstanding Gates

- S11-A/B 无待补 Review；本轮 C31 自查无 blocking finding，但用户未确认其代码 Review。用户已决定不提交该验证工程，故不以它阻塞 S11 文档收口，也不将本次范围决定写成用户 Ownership PASS。
- 平台完整 ADR、Identity 首个合同、跨服务认证／故障、前端迁出与仓库命名仍待后续决策。保存草案不等于批准全部设计，也不要求本轮冻结所有未来接口。
- 本次只提交项目文档；merge / 开新分支暂缓，验证工程保持本地未跟踪。后续使用时须另查实际 Git 状态。
- S11 整体 PARTIAL，迁移后重新定义下一 slice；没有 S11 全页面端到端验收或 S12 closeout PASS。
- S9 process-recovery、model result unknown、长期 qualification / Memory / Mastery、Hosted 容量与 TLS 的既有局限保留。测试不证明真实日语诊断质量、学习有效性或用户新掌握等级。
