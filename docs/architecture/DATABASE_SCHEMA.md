# Database Schema

> 当前 PostgreSQL physical schema inventory。覆盖 Flyway `V1`–`V17`、共 17 张表。
>
> 本文用于快速理解“每张表记录什么、表之间如何关联”；实际 column、constraint 与 migration 顺序仍以 `server/src/main/resources/db/migration/` 为 authority。

## 1. Relationship Overview

### Identity / Auth

```text
app_user 1 ── N language_profile
app_user 1 ── N auth_identity
auth_identity 1 ── 0..1 local_password_credential
single_user_instance 0..1 ── 1 app_user
```

### Model Execution / Planner

```text
app_user 1 ── N model_call_job
language_profile 0..1 ── N model_call_job
model_call_job 1 ── 0..1 model_call_text_generation_result

model_call_job 1 ── 0..1 planning_run
planning_run 1 ── 1..8 planning_run_candidate
planning_run 1 ── 0..1 learning_task
app_user + language_profile 1 ── N learning_task
```

`planning_run_candidate` 的 `1..8` 是 application invariant；DB 约束单个 `candidate_index` 为 `0..7` 并禁止同一 run 内重复 material identity。Deterministic planner 创建的 `learning_task` 可以没有 `planning_run`。

### Practice / Assessment

```text
learning_task 1 ── 0..1 practice_session
practice_session 1 ── 0..N practice_response
practice_session 1 ── 0..1 deterministic_assessment
deterministic_assessment 1 ── 0..N deterministic_step_assessment
```

### Semantic Evaluation

```text
practice_session 1 ── 0..1 evaluation_run
model_call_job 1 ── 0..1 evaluation_run
evaluation_run 1 ── 0..1 validated_semantic_candidate
validated_semantic_candidate 1 ── 0..20 validated_semantic_claim
practice_response 1 ── 0..N validated_semantic_claim
```

成功的 semantic evaluation 即使没有 claim，也会保存 `validated_semantic_candidate` header；失败的 run 不保存 candidate/claim。

## 2. Table Inventory

### Identity / Auth

| Table | 记录内容 | Identity / Cardinality | 主要关联 | Migration |
| --- | --- | --- | --- | --- |
| `app_user` | 应用用户根记录与创建时间。 | `id`；一个用户可有多个 language profile 与 auth identity。 | 被 `language_profile`、`auth_identity`、`model_call_job`、`learning_task`、`planning_run` 引用。 | `V1` |
| `language_profile` | 某用户某门目标语言的学习 workspace。 | `id`；`(user_id, language_code)` 唯一。 | `user_id → app_user`；以 `(id, user_id)` 支撑跨表 ownership 校验。 | `V1`, `V4` |
| `auth_identity` | 登录身份映射；当前 provider 为 `LOCAL_EMAIL`。 | `id`；`(provider, provider_subject)` 唯一。 | `user_id → app_user`；可有一个 password credential。 | `V2` |
| `local_password_credential` | Local identity 的 password verifier 与更新时间。 | `auth_identity_id` 同时为 PK/FK，因此每个 identity 至多一条。 | `auth_identity_id → auth_identity`，identity 删除时 cascade。 | `V2` |
| `single_user_instance` | Self-hosted single-user 模式的一行式用户绑定。 | 固定 `singleton_key = true`；`user_id` nullable 且唯一。 | `user_id → app_user`；不承载学习状态。 | `V3` |

### Shared Model Execution

| Table | 记录内容 | Identity / Cardinality | 主要关联 | Migration |
| --- | --- | --- | --- | --- |
| `model_call_job` | 一次受控 Model 调用的 purpose、operation、workflow、provider/model、execution/consumption lifecycle 与安全失败分类。 | `id`；可选绑定一个 language profile。 | `user_id → app_user`；`(language_profile_id, user_id) → language_profile`。不保存 Credential、完整 request 或 raw provider response。 | `V4`–`V7` |
| `model_call_text_generation_result` | `TEXT_GENERATION` 成功后的 portable generated text、finish reason 与可选 token usage。 | `job_id` 为 PK，每个 Job 至多一条。 | `(job_id, model_operation) → model_call_job`；不是 raw provider response。 | `V4` |

### Planner

| Table | 记录内容 | Identity / Cardinality | 主要关联 | Migration |
| --- | --- | --- | --- | --- |
| `learning_task` | 已持久化学习任务：exact material identity、语言、难度、时长、场景、goal、task type、planning reason、可选 recommendation reason 与 lifecycle。 | `id`；`(material_id, published_version)` 固定内容版本；status 为 `PLANNED/STARTED/COMPLETED`。 | 归属 `app_user + language_profile`；可由一个 terminal `planning_run` 绑定；可启动至多一个 `practice_session`。不保存 material body。 | `V8`, `V15`, `V17` |
| `planning_run` | Optional Planner enrichment 一次运行的 Job 绑定、workflow version、状态、最终 task 或 fallback reason。 | `id`；`model_call_job_id` 唯一；`learning_task_id` nullable 且唯一。 | 归属 `app_user + language_profile`；`model_call_job_id → model_call_job`；terminal 后绑定同 ownership 的 `learning_task`。 | `V16`, `V17` |
| `planning_run_candidate` | 该 run 实际提供给 Model 的 ordered exact material candidate snapshot。 | `(run_id, candidate_index)`；同 run 的 material identity 唯一；index `0..7`。 | `run_id → planning_run`；index `0` 是 deterministic fallback candidate。 | `V16` |

### Practice / Deterministic Assessment

| Table | 记录内容 | Identity / Cardinality | 主要关联 | Migration |
| --- | --- | --- | --- | --- |
| `practice_session` | 一次 task practice 的 lifecycle 与开始/完成时间。 | `id`；`task_id` 唯一，因此一个 task 至多一个 session。 | `task_id → learning_task`；拥有 responses、deterministic assessment，并可被 evaluation run 使用。 | `V9` |
| `practice_response` | 某 session/step 首次接受的 learner text、提交时间及四类 support exposure snapshot。 | `(session_id, step_id)`，每个 step 至多一条。 | `session_id → practice_session`；可被 semantic claim 精确引用。它不记录 correctness、长期 Evidence 或 Model output。 | `V9`, `V14` |
| `deterministic_assessment` | Session-level deterministic assessment header、policy version 与 duration。 | `session_id` 为 PK，每个 session 至多一条。 | `session_id → practice_session`；拥有 step assessments。 | `V10` |
| `deterministic_step_assessment` | 每个 assessed step 的 step kind 与 deterministic outcome。 | `(session_id, step_id)`。 | `session_id → deterministic_assessment`；只表示本次确定性结果，不是长期 learning state。 | `V10` |

### Semantic Evaluation

| Table | 记录内容 | Identity / Cardinality | 主要关联 | Migration |
| --- | --- | --- | --- | --- |
| `evaluation_run` | 一次 session semantic evaluation 的 Job、workflow version、lifecycle、grounding rejection 或安全失败原因。 | `id`；`session_id` 与 `model_call_job_id` 均唯一。 | `session_id → practice_session`；`model_call_job_id → model_call_job`。 | `V11`, `V13` |
| `validated_semantic_candidate` | Java validation 后的 evaluation header：session、exact material identity、rubric、target language 与 grounding policy。 | `evaluation_run_id` 为 PK；`session_id` 唯一。 | `(evaluation_run_id, session_id) → evaluation_run`；成功且零 claims 时仍存在。 | `V12` |
| `validated_semantic_claim` | 通过 strict validation 与 source grounding 的单条 semantic issue：原文定位、issue type、explanation、confidence。 | `(evaluation_run_id, claim_index)`；index `0..19`。 | 归属 `validated_semantic_candidate`；`(session_id, source_turn_id) → practice_response`，确保引用真实 learner response。 | `V12` |

## 3. Reading Rules

- `Job` 表示受控技术执行；`Run` 表示 Planner/Evaluator 业务 workflow；`Session` 表示用户实际练习。三者不能互相替代。
- 用户与语言归属优先由 composite FK 校验，避免只凭 `user_id` 或临时 `language_code` 推断 workspace。
- `material_id + published_version` 是 exact material identity；数据库记录引用，不复制 built-in material body。
- `practice_response` 是 raw learner input；`validated_semantic_claim` 是通过 Java schema/semantic/grounding validation 的 candidate evidence。二者都不会直接修改长期 learner state。
- 当前 schema 尚未包含完整 Learning Memory / long-term Weakness aggregation tables；不要从本清单推断这些能力已实现。

## 4. Maintenance Rule

新增或修改 Flyway migration 时，如果改变 table、FK、unique/cardinality、ownership 或表的 authority，应在同一 delivery slice 更新本文。本文是可读导航，不替代 migration 与 PostgreSQL constraint。
