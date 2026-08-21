# AI Language Tutor — Project Status

> Last updated: 2026-08-21
> Current Phase: M0 — Engineering Foundation & Language Workspace
> Current Gate: M0-S2 / READY_TO_COMMIT
> Production implementation: M0-S2 COMPLETE

## Approved Decisions

- 四个 pending modules 的 V1 裁剪已确认；
- M0–M6 的 Phase 顺序已确认；
- Architecture Baseline 已确认；
- V1 Scope v1.3 已纳入当前变更，正式范围记录在 `docs/product/V1_SCOPE.md`；
- Phase Gate 与 M0 slices 记录在 `docs/planning/V1_PHASE_PLAN.md`。
- M0-S1 使用 Java 25、Spring Boot 4.1、Maven、Node.js 24、Vue 3、TypeScript 与 Vite；backend/frontend 保持独立 build。
- M0-S2 使用 Docker Compose 运行 PostgreSQL 18 + pgvector 0.8.6 与 Redis 7.2；backend 通过 externalized configuration 连接，并只暴露 Actuator health endpoint。

## Completed Review

1. M0-S1 Spring Boot 与 Vue application skeleton；
2. M0-S1 backend/frontend 独立 build 与 startup；
3. `V1_SCOPE.md` v1.3 的 Product Audience、Success Model 与 Project Goal Priority；
4. M0-S2 Compose service、PostgreSQL 18 volume layout、loopback exposure 与 host/container port boundary；
5. M0-S2 pgvector availability 与 Flyway-owned installation boundary。

## Current Slice

```text
Selected slice: M0-S2
Implementation changes: local PostgreSQL/pgvector and Redis baseline, backend connection and health configuration
Verification:
  - Docker Compose configuration validation: PASS
  - PostgreSQL 18.6 container health: PASS
  - pgvector 0.8.6 availability without installation: PASS
  - Redis container health and PING: PASS
  - Backend Java 25 clean context test without Docker dependency: PASS
  - Backend Actuator health with db=UP and redis=UP: PASS
  - External PostgreSQL port override (15432 due local 5432 conflict): PASS
```

## Next Action

M0-S2 Diff Review 与 Human Ownership Check 已完成，当前可以进入人工 Commit Checkpoint；不自动开始 `M0-S3`。

## Blockers

None. 本机端口 `5432` 已被其他服务占用，runtime verification 通过 `DATABASE_PORT=15432` 完成，配置 override 已验证。
