# AI Language Tutor — Project Status

> Last updated: 2026-10-11 — S10 scoped User Review 接受；READY_TO_COMMIT，等待用户 Commit Decision。
> 当前状态入口；详细合同在 Feature / Phase / ADR，历史批次在 [历史快照](history/PROJECT_STATUS_2026-10-10.md)。
> 以下运行时测试与 Code Review 均为 prior evidence；本次新增 User Review 记录，只做文档检查，不把历史 PASS 当成本轮 fresh 验证。

## Current Gate

| 项目 | 当前状态 |
|---|---|
| Phase | M1 — Minimum Text Practice Loop |
| Slice | M1-S10 — Japanese Validation Pack |
| Implementation / Verification / Code Review | prior PASS；当前资源/配置范围 User Review 已接受 |
| Ownership Gate | READY_TO_COMMIT（仅 S10）；不提高模块掌握等级，不评定底层 Java 新 UNDERSTOOD |
| Production baseline | M1-S8T merged to main：PR #25 / `c2dbb4f` |
| Current branch / HEAD | `architecture/M1S9-OptionalPlannerEnrichment` / `a2f7d47`（本次 Git 核对；后续须重查） |
| Worktree | DIRTY：既有 S10 material / rubric / tests 与文档修改；本次仅同步 Review 与 Gate |
| Next slice | M1-S11 未批准开始；本次无 commit / push / merge |

`CURRENT_HANDOFF.md` 仍是 2026-09-16 的 S9 快照，HEAD / Gate 已 stale，不代表当前分工或下一动作。
本次未刷新快照；接手须核对 Git、当前合同与本文件，不遵循旧快照中的“不开始 S10”等过时状态。

## Next Action

用户决定是否提交 S10，以及本次提交纳入哪些文件。Worktree 同时包含此前协作规则 / 文档精简修改，
提交范围须与 S10 资源 / 测试修改区分；`READY_TO_COMMIT` 不代表允许全部暂存或自动 commit。
不开始 M1-S11；后续进入 S11 仍需单独确认 Scope。

## Active Decisions / 当前批准边界

- **M1-S10（2026-10-09）：**批准 `ja + zh-cn` 的单个 vertical slice，由 Codex full delegation；
  两个普通 PRACTICE step（EXACT + SEMANTIC_ONLY）、shared evaluator protocol prompt、独立 Japanese versioned rubric。
  不新增 schema、API、dependency、module、第二套 workflow 或通用 Japanese equivalence engine。
  详细合同与实现证据见 [M1 Feature](../features/M1_MINIMUM_TEXT_PRACTICE.md) §7.13、§14、§17。
- **协作（2026-10-10）：**User + Codex；Codex 负责获批实现、测试、验证，用户负责 Architecture / Scope /
  Commit 与独立 Critical Review。共同决策 → 代码导读 → 用户 Review；Human Core 按学习价值预选，
  Explain Back 仅必要澄清，自查不冒充独立作者 Review，不自动升级 Ownership。
  规则见 [AGENTS 协作合同](../../AGENTS.md#5-user--codex-collaboration--项目内协作)。
- **S10 Review（2026-10-11）：**源码导读与用户设计讨论后，用户接受本次资源/配置扩展并要求进入下一步。
  记录在 [Ownership Review](../ownership/OWNERSHIP_MATRIX.md#m1-s10-scoped-user-review--2026-10-11) 与
  [LEARN-002](../ownership/LEARNING_LOG.md#learn-002--新语言验证共享工程流程不等于真实语言质量已验证)。
  Human Touch `NOT_REQUIRED`（既有 full delegation）；模块等级不变，完整复盘保持 `PROVISIONAL`。
- **Guided Language Learning：**已批准纳入 V1 P0 与 M1–M6；M1-S8T 完成，S11 承接最小 UX，
  M2 聚合适应、M3 内容生产、M4 迁移复习、M5 音频、M6 验证。分配不授权下一 Slice。
  见 [Guided Language Learning](../features/GUIDED_LANGUAGE_LEARNING.md) 与 [Phase Plan](V1_PHASE_PLAN.md)。
- **M2C：**Product / Architecture Scope 已批准；放在 M2 exit 后、M3 implementation 前，不重编号 M3–M6。
  首版受控 `en + zh-CN` text scenario；具体 schema / API / event / Prompt / Context / Slice 未批准实现。
  见 [Adaptive Scenario Conversation](../features/ADAPTIVE_SCENARIO_CONVERSATION.md)。

## Delivery Summary / 已完成能力入口

| 范围 | 记录状态 | 详细来源 |
|---|---|---|
| M0 Engineering Foundation / Model Call Job | Integrated closeout PASS；S9 baseline `b88606c` | [Phase Plan](V1_PHASE_PLAN.md)、[历史记录](history/PROJECT_STATUS_2026-10-10.md#completed-review) |
| M1-S1–S7 Content / Planner / Practice / Grounding | Implementation / Review / Ownership 完成 | [M1 Feature](../features/M1_MINIMUM_TEXT_PRACTICE.md)、[Flows](../flow/README.md) |
| M1-S8 Evaluator integration | 完整闭环 Ownership PASS；merged `7776111` | [M1 Feature](../features/M1_MINIMUM_TEXT_PRACTICE.md)、[Evaluation API Flow](../flow/evaluation-api-orchestration.md) |
| M1-S8T Minimum Text Teaching | Ownership PASS；merged `c2dbb4f` | [Guided Feature](../features/GUIDED_LANGUAGE_LEARNING.md)、[Practice Flow](../flow/practice-session-lifecycle.md) |
| M1-S9 Optional Planner Enrichment | Implementation / Review / external verification / Ownership COMPLETE；`d68d70f`…`786f124` | [Planner Enrichment](../features/PLANNER_ENRICHMENT.md)、[Planning Flow](../flow/owner-scoped-learning-task-planning.md) |
| M1-S10 Japanese Validation Pack | prior Code Review PASS；scoped User Review 接受；READY_TO_COMMIT | [M1 Feature §7.13](../features/M1_MINIMUM_TEXT_PRACTICE.md) |

完成与 commit / merge、代码理解等级是不同维度；Model Gateway、BYOK / Provider Configuration 保持既有 L2，
不因这份摘要或测试通过升级。详细掌握证据见 [OWNERSHIP_MATRIX](../ownership/OWNERSHIP_MATRIX.md)。

## Verification Evidence / 当前 Slice 的既有证据

| 日期 / 性质 | 范围与结果 | 没有证明的内容 |
|---|---|---|
| 2026-10-09 / PRIOR PASS | affected unit 211/211；disposable PostgreSQL 18.6、empty schema Flyway V1–V17；affected integration 56/56 | 不代表 live Provider 质量或 frontend UX |
| 2026-10-10 / PRIOR PASS | gap-fix unit 27/27、integration 18/18，均无 skip；Japanese 3 + existing dispatch 2 / consumption 13 | 未重跑全部 211/56；数量不能替代覆盖判断 |
| 2026-10-10 / PRIOR Code Review PASS | shared request、durable success / grounding rejection / model failure 缺口关闭；无 blocking finding | Codex 自查不替代用户 Review / Ownership |
| 本次 Gate 同步 / NOT_RUN | 未重跑 Java、PostgreSQL、live Provider 或前端验证 | 文档检查不是运行时验证 |

S10 使用独立临时 PostgreSQL，未使用 primary database；临时容器已清理（既有证据）。
无 Production Java / schema / API change；现有 Behavior Flow 仍适用。
更早的迁移、测试数量、skip、修复与作者记录原样保留在 [历史快照](history/PROJECT_STATUS_2026-10-10.md)，
不在状态入口复制完整批次。

## Blockers / Risks / UNKNOWN

- M1-S10 无既有已知 Code Review、Architecture 或 PostgreSQL verification blocker；
  scoped User Review 已接受；未确认底层 Java 模块的新掌握程度，不用额外考试代替本次有限范围判断。
- live Provider quality、frontend UX 不在 S10 验证范围；日语 Prompt fork 未实现。
- S9 retry / process-recovery 与 M2 learner-state context 不在已完成范围；
  crash / background completion 的 NOT_READY 局限留待已规划的后续 Gate 评估，不顺手补实现。
- Scheduler、automatic retry、legacy CREATED / RUNNING recovery、SSE / WebSocket 未实现；
  Trace 仅安全 logging metadata，不能宣称 durable Trace / 完整产品端到端已完成。
- Hosted TLS、Browser local / session storage Credential UI、业务 Agent Workflow、
  live DeepSeek Credential / network 未验证；Hosted model-call 与 password-hash capacity 仍为 PROVISIONAL，
  按既定 Scope 在 M6 目标硬件验证。
- Production / verification 判断仍须核对 source、tests、真实 Diff；历史记录不能替代 fresh Evidence。

## Documentation Maintenance

本文件只维护当前 Gate、有效决策摘要、风险、下一动作与详细来源链接。
历史归档不是新 Scope authority，也不撤销其中的历史批准决定；最新正式合同仍按 AGENTS 的优先级核对。
历史作者与验证结果不改写、不包装成新结果；本次只收口 S10 Gate，没有提高模块掌握等级或授权生产代码 / 新 Slice。
Phase Plan 的长历史与 Feature 正文不在本次整理范围，后续可独立决定是否精简。
