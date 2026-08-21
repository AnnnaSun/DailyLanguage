# AI Language Tutor — Project Status

> Last updated: 2026-08-22
> Current Phase: M0 — Engineering Foundation & Language Workspace
> Current Gate: M0-S3 / COMPLETE
> Production implementation: M0-S3 COMPLETE

## Approved Decisions

- 四个 pending modules 的 V1 裁剪已确认；
- M0–M6 的 Phase 顺序已确认；
- Architecture Baseline 已确认；
- V1 Scope v1.3 已纳入当前变更，正式范围记录在 `docs/product/V1_SCOPE.md`；
- Phase Gate 与 M0 slices 记录在 `docs/planning/V1_PHASE_PLAN.md`。
- M0-S1 使用 Java 25、Spring Boot 4.1、Maven、Node.js 24、Vue 3、TypeScript 与 Vite；backend/frontend 保持独立 build。
- M0-S2 使用 Docker Compose 运行 PostgreSQL 18 + pgvector 0.8.6 与 Redis 7.2；backend 通过 externalized configuration 连接，并只暴露 Actuator health endpoint。
- M0-S3 使用 PostgreSQL 18 native UUIDv7 identity、Flyway 12 与 MyBatis-Plus 3.5.17 / MyBatis Mapper XML；`languageProfileId` 是单列主键，`(user_id, language_code)` 保证单用户单语言 workspace 唯一。
- User → LanguageProfile foreign key 使用 `ON DELETE RESTRICT`；M0-S3 不实现 physical / logical deletion，lifecycle decision 已记录到 Backlog。
- Language Profile 读取通过 `languageProfileId + userId` scoped query 建立 persistence ownership boundary；可信 `UserContext` 留到 M0-S4。
- SQL variables 只通过 `#{}` parameter binding；禁止 `${}`、Java SQL annotation / string、客户端 SQL fragment 与 `last` / `apply` / `SqlRunner` 等拼接入口。

## Completed Review

1. M0-S1 Spring Boot 与 Vue application skeleton；
2. M0-S1 backend/frontend 独立 build 与 startup；
3. `V1_SCOPE.md` v1.3 的 Product Audience、Success Model 与 Project Goal Priority；
4. M0-S2 Compose service、PostgreSQL 18 volume layout、loopback exposure 与 host/container port boundary；
5. M0-S2 pgvector availability 与 Flyway-owned installation boundary。
6. M0-S3 UUIDv7 identity schema、Language Profile ownership boundary 与 deletion constraints；
7. M0-S3 MyBatis Mapper XML persistence path、UUID TypeHandler 与 SQL injection safety boundary。

## Current Slice

```text
Selected slice: M0-S3
Implementation changes: Flyway identity migration, UUIDv7 User / Language Profile persistence, MyBatis Mapper XML and ownership-scoped query
Verification:
  - Java 25 default test suite without Docker dependency: PASS
  - Mapper XML raw substitution / plain statement safety checks: PASS
  - Flyway migration against PostgreSQL 18.6: PASS
  - pgvector extension installation through Flyway: PASS
  - PostgreSQL-generated UUIDv7 for User and Language Profile: PASS
  - BCP 47 language code validation, normalization and per-user uniqueness: PASS
  - missing User foreign-key rejection: PASS
  - User deletion RESTRICT with existing Profile: PASS
  - languageProfileId + userId ownership-scoped lookup: PASS
  - SQL injection payload rejection followed by successful repository operation: PASS
  - External PostgreSQL port 15432 integration run: PASS
```

## Next Action

M0-S3 implementation、verification、Diff Review 与 Human Ownership Check 已完成。本次 commit 后停在 M0-S3；进入 `M0-S4` 前需要新的 Scope Review。

## Blockers

None. 本机端口 `5432` 已被其他服务占用，runtime verification 通过 `DATABASE_PORT=15432` 完成，配置 override 已验证。
