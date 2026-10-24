# AI Language Tutor — Repository AGENTS

> 项目级入口合同：常驻硬约束、协作边界与按需读取导航。
> 通用 A/B/C 流程、Small Diff、Git Safety、命名与验证纪律继承用户级 Harness；
> 详细 workflow 使用对应 Skill，不在本文件重复维护。

## 1. Project Identity / 项目定位

AI Language Tutor 是 provider-agnostic、stateful AI Language Tutor Agent。
目标是真实交流、阅读理解、自然表达与流利度，不是题库、背单词或仅有 Prompt 的聊天 Demo。

核心闭环：
Persistent Learner Model → Planner → Practice → Evaluator → Learning Memory → Profile Update → Re-planning。

产品成功关注“以前不能使用 → 现在能在真实场景使用”，而不是题量、打卡、词汇量或考试分数。
详细产品与模块约束见 [PROJECT_CONTRACT](docs/architecture/PROJECT_CONTRACT.md)；
该文件是从旧 AGENTS 移出的既有合同，不代表相关能力全部已实现或已进入当前 Scope。

## 2. Always-on Invariants / 常驻硬约束

- **语言隔离：**学习状态、历史、Evidence、Memory 与 Planner Context 按 `languageProfileId` 归属；
  不跨语言共享，不在各模块仅用 `userId + languageCode` 临时推断 workspace。
- **AI / Java authority：**LLM 生成 soft decision、诊断、候选与 Evidence；
  Java 持有 validation、permission、aggregation、deterministic transition 与 persistence authority。
  把确定性业务规则移入 Prompt 属于 Architecture-sensitive Change。
- **长期状态：**Raw Evidence → Aggregated Memory → Long-term State；同时记录成功与失败。
  一次模型输出或一次错误不能直接变成 ACTIVE Weakness、MASTERED 或 Level Upgrade。
- **职责隔离：**Planner 决定训练意图，不生成完整材料、评分或修改 Memory / Level / Weakness；
  Evaluator 只生成 Session-level Evidence，不直接决定长期能力状态；失败保留 Practice，不污染长期状态。
- **真实使用：**Grammar 作为 Repair 而非主路径；Conversation 不退化为逐句语法批改；
  Repair Success 不等于 Transfer Success 或长期掌握。
- **状态真相：**RAG Result 是 Context，不是长期状态事实；Profile 是 compact summary，不是完整历史库；
  Planner 不维护第二套 mastery truth，Review Scheduler 不承担完整 Planner。
- **受控写入：**Agent 通过明确 Tool Contract 提交 Evidence / 请求动作，不获得任意 Application / DB 写权限；
  Structured Output 必须经适当 schema、enum、semantic validation 与 qualification。
- **Provider boundary：**业务模块通过已有 Model Gateway / Adapter 调用模型，不直接绑定 concrete provider SDK；
  共享 workflow / contracts，语言差异集中在配置、Prompt、taxonomy，不散落语言条件分支。
- **Credential / Privacy：**BYOK-first；Hosted Credential 只经 HTTPS 临时传给 Backend，不落 PostgreSQL /
  Redis / Trace / Log；Secret 不进 metrics、exception 或 debug dump。
  Trace 优先 metadata，不无条件记录完整私密 Prompt / Conversation，不修改学习状态。
- **部署与状态权限：**Hosted / Self-hosted 共用核心代码，环境差异放在配置 / adapter；
  Spring Boot 持有核心 domain、authorization、长期状态与 persistence authority，不由浏览器独占真相。
- **Context / Eval：**任务只获得所需 Context；禁止完整历史无差别塞入 Prompt。
  AI 重要行为变化需考虑 Regression Eval，不能仅凭“看起来不错”判断。

这些约束被削弱或发生冲突时 STOP AND REVIEW，不能当作普通实现细节静默修改。

## 3. Task Entry / 接手与按需读取

每次接手按顺序：
本文件 → [PROJECT_STATUS](docs/planning/PROJECT_STATUS.md) →
当前已批准 Phase / Feature / Slice 的相关章节。

修改前核对 branch、HEAD、status 和相关 Diff；进度记录与 Git、source、tests 或正式决定冲突时，
标记 stale / UNKNOWN，在受影响范围内停止修改。进度记录不是 Scope authority 或 concurrency lock。

先找标题、symbol、关键词，再读入口、直接依赖和相关测试；不默认加载整个文档目录。
按下表读取详细合同的相关章节，不要求每个任务通读整个合同；已选择的 Skill 仍须完整读取。

| 任务触及 | 必读详细合同 / 入口 |
|---|---|
| 产品方向、Grammar、Practice、Conversation、Transfer | PROJECT_CONTRACT §1–3、§9–10、§14；当前 Feature |
| Profile、语言归属 | PROJECT_CONTRACT §4、§7 |
| Memory、Weakness、长期状态 | PROJECT_CONTRACT §5–6、§12–14 |
| Planner、Review scheduling | PROJECT_CONTRACT §8、§15；当前 Feature |
| Evaluator、Structured Output | PROJECT_CONTRACT §6、§11、§20、§24；对应 Behavior Flow |
| RAG、Context、Tool | PROJECT_CONTRACT §16–18；相关 Architecture / Flow |
| Model Gateway、BYOK、Trace | PROJECT_CONTRACT §19–24；相关 Architecture / Flow |
| 语言特定 AI、部署、前后端权限 | PROJECT_CONTRACT §25–27 |
| Scope / Architecture 变化、漂移检查 | PROJECT_CONTRACT §28、§30、§37–38 |

Architecture 导航使用 [SYSTEM_OVERVIEW](docs/architecture/SYSTEM_OVERVIEW.md)、
[MODULE_MAP](docs/architecture/MODULE_MAP.md)、[DATA_FLOW](docs/architecture/DATA_FLOW.md)、
[AGENT_FLOW](docs/architecture/AGENT_FLOW.md)；最终行为判断必须核对代码与测试。
Planner、Conversation Runtime、Evaluator、Learning Memory / Weakness / Skill Aggregation、Content Pipeline、
RAG、Tool / Context / Model Gateway / BYOK、Structured Output、Trace / Eval 与语言隔离的非机械修改
默认 A — Critical；DTO / Mapper / 样板测试仍可单独归 B/C。
没有足够证据时扩大读取；能回答“改哪里、为什么、影响谁、如何验证”后停止探索。
实质性任务 / Scope 扩大时判断并使用 `token-efficient-work`，不为普通任务重复全库扫描。

## 4. Scope and Documentation Authority / 范围与文档权威

判断当前是否实现，按以下顺序检查：

1. Current approved Phase / Feature / Slice decision；
2. [V1_SCOPE](docs/product/V1_SCOPE.md) 与 Full Backlog Baseline；
3. Detailed Function Design；
4. Core User Flow；
5. PRD。

长期设计或 Backlog 中存在某能力，不等于已批准当前实现；延期不等于删除长期设计。
真实文档冲突必须指出出处、影响和待决问题，由用户决定，不静默选择版本。
项目核心闭环、语言隔离、AI / Java authority、模块职责、Tool / Provider / Credential / 双部署边界改变，
必须先提出 Current Situation、Problem、Proposed Change / Alternatives、Trade-offs、Impact，等待 Human Approval，
再按需记录 ADR；详细影响边界见 PROJECT_CONTRACT §37。

重大获批变化只同步真正受影响的 Architecture / ADR / Feature 文档；小修改不更新全部文档。
已实现的重要跨模块行为，按 [Flow 规则](docs/flow/README.md) 创建 / 更新 Behavior Flow，
在 Diff Review / Ownership 前核对 source 与 verification；未来设计不能混入已实现流程。
Status 只保留当前 Gate、摘要与证据入口；Phase Plan 管路线图；Feature 管合同与详细决策；
历史证据保留在归档，不把重复长正文维护成多个 Source of Truth。

## 5. User / Codex Collaboration / 项目内协作

当前默认 User + Codex；用户实现已选 Human Core，Codex 承担其余获批的开发、测试与验证，
不默认安排其他 Coding Agent 实现或交接。
恢复多 Agent 分工须用户明确指定。Architecture、Scope、Commit Decision 始终由用户负责。

当前以工程学习与 Human Code Ownership 为优先，通过真实项目交付理解架构、陌生模块与 AI workflow，
能够独立 Review、优化并解释真实工程问题；不退化成纯课程或只批准 Agent 成品方案。

- A 类先由 Codex 给出 Evidence、约束、风险和少量方案，用户参与 Invariant、边界与 trade-off 判断，
  共同收敛 Current Slice Contract；合同写明 1–2 个 Learning Focus、实现作者、User Review 入口和待审判断。
  B 类按风险简化，C 类不增加固定学习问答。
- 有明确学习价值且范围可控的关键逻辑或测试，优先选为 Human Core，由用户先实现；
  Codex 在 Scope 阶段提出选择理由与边界，由用户确认分工，不强制每个 Slice 人工编码。
  Human Core 必须触及 Domain Rule、状态转换、Validator、failure behavior 或关键测试；
  rename、注释、格式化和机械修改不算。
- 已选 Human Core 写清代码边界、行为、Invariant、Acceptance Criteria、最小 scaffolding 和验证；
  停在 `HUMAN_CORE_READY` 后由用户实现。Codex 不抢先给出完整替换答案、不覆盖用户实现；
  取消 / 转交仍须用户明确决定，不因规则调整取消已有约定。
- Human Core 采用逐级辅助：用户先尝试 → 针对卡点给思路或定位提示 → 用户再试 →
  必要时给伪代码或局部示例 → 用户再试 → 仍无法完成且用户要求示范 / 转交后，由 Codex 完整实现并导读。
  求助本身不等于转交，每次只给能推动下一步的提示，留出用户尝试的机会，不设固定尝试次数；
  用户明确要求完整实现或直接委托时可以跳过提示阶段，无需重复确认。
  Codex 接手后解释关键判断、状态变化和失败行为，再由用户结合真实代码 Review；
  如实区分独立实现、提示后实现与 Codex 示范，不把 AI 编码记为用户 hands-on，也不自动提高 Ownership。
- Codex 负责获批 Slice 中分配给自己的实现、Unit / Module Tests、Docker / PostgreSQL / Redis / Flyway /
  Integration / E2E 验证和获准修复；环境 / migration 文件修改同样须在批准 Scope 内。
  Review 请求本身不授权修复；Human Core finding 交回用户，只有明确委托后才接管。
- 实现后先从具体请求导读：业务角色、入口、对象与参数、调用、State Read / Mutation、
  AI / Java authority、成功 / 失败结局、所选与替代方案；按需解释陌生模块，不要求平均阅读 boilerplate。
- Codex 对自己的代码检查属于自查，不冒充独立作者 Review；不能替代用户 Critical Review。
  用户建议以代码、测试或实验回应，区分 Finding、trade-off 与 Scope 外建议，不自动变成重构。
  用户可认为方案合理，不必制造 Bug、Finding 或人工修改证明参与。
- 先代码导读，再讨论理解缺口；Explain Back 仅必要时使用，不是默认考试；
  未答题或选择先听解释不等于 `NOT_UNDERSTOOD`。Agent 解释、测试通过、笼统同意均不自动证明 Ownership。
  Ownership 以真实代码追踪、设计判断、failure reasoning 与独立 Review 讨论为证据；
  方法见 [OWNERSHIP_MATRIX](docs/ownership/OWNERSHIP_MATRIX.md)，历史掌握等级不因本次规则改变。

闭环：
Evidence / Alternatives → User + Codex Decision → Slice Contract / Human Core Selection →
User Implementation + Graduated Assistance (if selected) → Codex Implementation / Tests → Critical Self-review / External Verification →
获准修复 / delta-only Review → Code Walkthrough → User Critical Review / Design Discussion →
必要澄清 → Engineering Decision Retrospective → User Ownership / Commit Decision。

有 blocking finding 保持 `REVIEW_BLOCKED`；Code Review 可继续且验证完成后，用户 Review / 理解待确认时
保持 `OWNERSHIP_PENDING`。不得仅凭自查 PASS 进入 `READY_TO_COMMIT`；
已约定 Human Core 未完成仍阻止该 Gate，除非用户明确调整分工。
导读 / Review / 复盘放在有意义的完整闭环后，不每个小 Slice 重复举行。
一次性汇总 blocking findings；修复先查增量 Diff 与受影响回归，candidate 稳定后集中必要外部 / wider 验证。
合同本身有问题时请求 Design / Scope Decision，不静默修改；不自动 commit、push、merge 或开始下一 Slice。

面试复盘沿用 [LEARNING_LOG](docs/ownership/LEARNING_LOG.md)：
真实问题 / 约束 → 备选方案 → 选择理由 → 代价 / 风险 → 验证 → 局限 / Revisit Trigger。
区分用户决策 / Review / 操作与 Agent 实现、讨论与实际实验，不虚构事故、指标或模型质量。
测试证据区分新增与已有回归、unit / integration / live Provider、fresh / prior / NOT_RUN，
说明证明与未证明的内容；不自动把 Agent 草稿标为 CONFIRMED / OPERATED，不另建第二套面试知识库。

## 6. Backlog / 范围外待办

开发发现范围外值得保留的工程事项：报告 → 记录 [BACKLOG](docs/planning/BACKLOG.md) → 继续当前 Scope，
不顺手实现。用户明确要求“记下来 / 以后优化”时读取文件、取下一个 IDEA-XXX、追加条目；
信息未知标 UNKNOWN，默认 Status: INBOX / Priority: UNASSESSED / Target: UNDECIDED。
仅提出想法但保存意图不明时，先询问是否记录。

记录不等于进入 V1 / Phase、批准实现或架构变化。明确 Scope Decision 后才由 INBOX → TRIAGED → PLANNED，
只同步真实受影响的计划 / 产品 / Architecture / ADR；最终正式决定不能只留在 Backlog。
TECH_DEBT 指当前存在的已知不足或取舍；Future Idea 不等于当前缺陷，二者不可混写。

## 7. 进度同步与恢复

使用 [PROJECT_STATUS](docs/planning/PROJECT_STATUS.md) 同步当前 Phase / Slice、Gate、Stop Point、
验证摘要、Blocker 与单一 Next Action；详细 Scope、Decision、Human Core 分工与验收证据保留在对应 Feature / Phase 文档。
不再维护独立 CURRENT_HANDOFF 快照，避免多个当前状态入口漂移。

获批决定、实现或验证使当前进度变化时，同步真正受影响的记录；不把历史验证描述为 fresh，
不能确认的意图、验证与 Ownership 标 UNKNOWN。恢复时仍须核对 Git 与源码，文档同步不授权自动 commit。

## Final Rule

真实语言使用 + Persistent Learner State + Evidence-driven Adaptation + Controlled Agent Runtime。
当继续产码与重要修改的理解、控制冲突时：STOP AND REVIEW。
