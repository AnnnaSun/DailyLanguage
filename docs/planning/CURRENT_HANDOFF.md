# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-03 16:10 CST
Updated By: Zcode
Handoff State: CURRENT
Handoff Reason: 用户明确要求刷新 handoff snapshot（AGENTS §34.2 第二条件；非额度触发）
```

## Branch / HEAD / Worktree

```text
Branch: codex/M1-S1（会话开始时在 main，会话中途由用户切换；HEAD 未变）
HEAD: 15e0ec8（= main 当前 HEAD，尚无新 commit）
Worktree Summary: 9 个 status 条目 = M1-S1 未提交改动（见 Uncommitted Changes，8 条）
  + 本 handoff 文件自身；无他人遗留修改；无 stash
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Slice: M1-S1 — Built-in Content boundary + English artifact
Slice Gate: READY_TO_COMMIT（Code Review PASS；Ownership Review UNDERSTOOD）
Stop Point: 等待用户 commit 决定；可选 Human Touch 小任务未做
```

## Approved Scope / Explicit Non-scope

- Approved：M1-S1 Current Slice Contract（2026-09-03 用户批准）——`LearningMaterialCatalog`
  read boundary、classpath Built-in loader（strict binding + manifest SHA-256 lineage +
  eager fail-closed startup validation）、`en + zh-cn` pack（2 个 FOUNDATION materials）、
  hash 工具、测试；catalog/schema 为 language-pair generic，只发布英语 pack。
- Explicit Non-scope：Planner（S2）、LearningTask persistence/DB migration（S3）、API（S4）、
  PracticeSession（S5）、deterministic matcher（S6）、semantic rubric 资源（S7+）、日语 pack（S10）、
  Vue UI（S11）、Content publish pipeline（M3）。

## Completed Work（本 slice，全部未提交）

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

- fresh（本会话实际运行）：
  - content 模块 `mvnw test -Dtest='com.dailylanguage.content.**'`：40/40 PASS（注释修改后复跑仍 40/40）；
  - 全量 `RUN_DATABASE_TESTS=true DATABASE_PORT=15432 ./mvnw test`：378 run / 0 failures /
    0 errors / 11 既有 conditional skip（= M0 基线 338 + 本 slice 40，完全对账）；
  - `GenerateBuiltInMaterialHash --check`：PASS。
- prior（引用 `PROJECT_STATUS.md`，本会话未复跑）：M0-S9 closeout、client build、migration 验证。
- not run：client build（本 slice 未触及 client）；compose 全栈启动（未改基础设施）。

## Uncommitted Changes

- 本 slice 修改（全部）：`server/src/main/java/com/dailylanguage/content/`（19 文件）、
  `server/src/main/resources/content/builtin/`（3 文件）、
  `server/src/test/java/com/dailylanguage/content/`（3 文件）、
  `server/tools/GenerateBuiltInMaterialHash.java`、docs 4 文件
  （PROJECT_STATUS / V1_PHASE_PLAN / M1_MINIMUM_TEXT_PRACTICE / MODULE_MAP）。
- 接手前已有修改：无（会话开始时 worktree clean）。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions（M1-S1 contract 内）：语言代码用 canonical lowercase BCP-47（与
  `LanguageProfileRepository.normalizeLanguageCode` 存库形式一致，非 canonical fail closed）；
  eager 启动校验；strict binding feature 集与 modelgateway `StructuredOutputValidator` 对齐但
  不跨模块依赖；hash 先于结构校验；`listAvailable` 按 materialId 排序保证可重放。
- Decisions（用户会话中决定）：补类注释（已做）；命名约定文档化、content 按 modality 分包、
  相应 Backlog 条目——均「暂时先不修改」，未记录。
- Blockers：无。
- Risks：本机 5432 端口是另一个 PostgreSQL 实例（凭据不符）；本项目 DB 在 **15432**
  （`.env` 已配），跑 DB 测试需 `DATABASE_PORT=15432` + `RUN_DATABASE_TESTS=true`。
- UNKNOWN：无。

## Next Action（单一）

由用户执行 M1-S1 commit 决定（可直接 commit 当前 Diff；commit 前可先完成可选 Human Touch
小任务：给 greeting-intro 的 EXACT 步骤加一个回答变体并用 hash 工具更新 manifest）。

## 需要用户完成的 Decision

1. Commit Decision：是否按当前 Diff 提交 M1-S1（commit message / 是否拆分由用户定）；
2. 可选：Human Touch 小任务做不做；
3. Commit 后：何时提出 M1-S2（Deterministic Planner core）Current Slice Contract。
