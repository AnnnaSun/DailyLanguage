# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-03 16:19 CST
Updated By: Codex
Handoff State: CURRENT
Handoff Reason: 用户明确要求完成 M1-S1 post-commit documentation closeout
```

## Branch / HEAD / Worktree

```text
Branch: codex/M1-S1
HEAD: d3eeadc（feat(content): 实现 M1-S1 Built-in 内容边界与 en+zh-cn 内置材料包）
Worktree Summary: DIRTY — 仅 M1-S1 post-commit closeout 的 4 个 documentation files；
  Production / tests / resources clean，无接手前遗留修改
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Slice: M1-S1 — Built-in Content boundary + English artifact
Slice Gate: COMPLETE（Code Review PASS；Ownership Review UNDERSTOOD；committed as d3eeadc）
Stop Point: M1-S2 SCOPE_NOT_APPROVED；只可提出 Current Slice Contract，不得直接实现
```

## Approved Scope / Explicit Non-scope

- Approved：M1-S1 Current Slice Contract（2026-09-03 用户批准）——`LearningMaterialCatalog`
  read boundary、classpath Built-in loader（strict binding + manifest SHA-256 lineage +
  eager fail-closed startup validation）、`en + zh-cn` pack（2 个 FOUNDATION materials）、
  hash 工具、测试；catalog/schema 为 language-pair generic，只发布英语 pack。
- Explicit Non-scope：Planner（S2）、LearningTask persistence/DB migration（S3）、API（S4）、
  PracticeSession（S5）、deterministic matcher（S6）、semantic rubric 资源（S7+）、日语 pack（S10）、
  Vue UI（S11）、Content publish pipeline（M3）。

## Completed Work（M1-S1，已提交为 `d3eeadc`）

1. M1-D1 Documentation Review 标记通过；`PROJECT_STATUS` / `V1_PHASE_PLAN` /
   `M1_MINIMUM_TEXT_PRACTICE` gate 同步至 M1-S1；
2. 新模块 `com.dailylanguage.content`：domain 13 个 typed 类型 + port；infrastructure
   loader / catalog / manifest 绑定 / SAM reader seam / fail-closed exception；
3. Built-in artifact：`manifest.json` + 2 个 `en + zh-cn` materials
   （`en-builtin-greeting-intro` / `en-builtin-cafe-request`，PROJECT_ORIGINAL / AGPL-3.0）；
4. `server/tools/GenerateBuiltInMaterialHash.java`（生成 / `--check`）；
5. 测试 3 类 40 个：真实 pack 加载、26 类 fail-closed 矩阵、多 pair 无 fallback、context 启动；
6. 类级 Javadoc 补齐（用户要求）；
7. `MODULE_MAP.md` 新增 Built-in Learning Material Boundary 物理 mapping 行；
8. Code Ownership Review 完成：Scope MATCH、无 blocking finding、Explain Back UNDERSTOOD。

## Verification Evidence

- fresh（本次 post-commit documentation closeout）：
  - branch / HEAD / clean starting worktree 核对：`codex/M1-S1@d3eeadc`；
  - closeout scope：仅 4 个 documentation files；Production / tests / resources 无修改；
  - `git diff --check`：PASS；M1-S1 stale gate / commit wording targeted search：PASS。
- prior（M1-S1 implementation / Review handoff evidence；本次 closeout 未复跑）：
  - content 模块 `mvnw test -Dtest='com.dailylanguage.content.**'`：40/40 PASS；
  - 全量 `RUN_DATABASE_TESTS=true DATABASE_PORT=15432 ./mvnw test`：378 run / 0 failures /
    0 errors / 11 既有 conditional skip（= M0 基线 338 + M1-S1 40）；
  - `GenerateBuiltInMaterialHash --check`：PASS；Code Review PASS；Ownership Review UNDERSTOOD。
- prior（引用 `PROJECT_STATUS.md`；本次未复跑）：M0-S9 closeout、client build、migration 验证。
- not run：backend tests、client build、compose 全栈启动、M1-S2 behavior（docs-only closeout）。

## Uncommitted Changes

- M1-S1 Production / tests / resources：none；已提交为 `d3eeadc`。
- Post-commit closeout docs：`CURRENT_HANDOFF.md`、`PROJECT_STATUS.md`、`V1_PHASE_PLAN.md`、
  `M1_MINIMUM_TEXT_PRACTICE.md`。
- 接手前已有修改：无；开始本次 closeout 时 worktree clean。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions（M1-S1 contract 内）：语言代码用 canonical lowercase BCP-47（与
  `LanguageProfileRepository.normalizeLanguageCode` 存库形式一致，非 canonical fail closed）；
  eager 启动校验；strict binding feature 集与 modelgateway `StructuredOutputValidator` 对齐但
  不跨模块依赖；hash 先于结构校验；`listAvailable` 按 materialId 排序保证可重放。
- Decisions（用户会话中决定）：补类注释（已做）；命名约定文档化、content 按 modality 分包、
  相应 Backlog 条目——均「暂时先不修改」，未记录。
- Blockers：无；M1-S2 仍需独立 Scope approval。
- Risks：本机 5432 端口是另一个 PostgreSQL 实例（凭据不符）；本项目 DB 在 **15432**
  （`.env` 已配），跑 DB 测试需 `DATABASE_PORT=15432` + `RUN_DATABASE_TESTS=true`。
- UNKNOWN：M1-S2 的 Current Slice Contract 尚未形成，Expected Files、data / API impact 与具体 verification
  commands 尚未批准。

## Next Action（单一）

提出 `M1-S2 — Deterministic Planner core` Current Slice Contract；完成 Design / Scope 后停止并等待用户批准，
不自动修改 Planner Production Code、schema 或 API。

## 需要用户完成的 Decision

1. 当前 4 份 post-commit closeout documentation 的 Review / Commit Decision；
2. M1-S2 Current Slice Contract 形成后的 Scope Decision。
