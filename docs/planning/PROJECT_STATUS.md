# AI Language Tutor — Project Status

> Last updated: 2026-08-24
> Current Phase: M0 — Engineering Foundation & Language Workspace
> Current Gate: M0-S4B2c / READY_TO_COMMIT
> Production implementation: M0-S4B2c IMPLEMENTATION / VERIFICATION / REVIEW / OWNERSHIP COMPLETE — COMMIT PENDING

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
- M0-S4 采用 `ADR-0002`：Hosted V1 使用 Spring Security、Argon2id local credential、Redis-backed server-side Session、CSRF 与 authenticated `UserContext`；不使用 browser-stored JWT。
- `app_user` 保持稳定 internal identity，login channel 通过 `auth_identity` 分离；multi-channel authentication 与 Account Linking 记录为 `IDEA-006`，不进入当前 M0-S4 implementation。
- Argon2id security parameters 以 `argon2id-v1` 在 code 中版本化；password-hash concurrency 是 hardware-dependent capacity parameter，DEV / TEST 与 Self-hosted safe default 为 1，Hosted 必须显式配置。
- M0-S4C2 必须实现 Rate Limit-before-Argon2、global password-hash concurrency gate、fail-fast saturation 与 provisional restricted-Container verification；Hosted capacity 到 M6 在目标硬件确认。
- M0-S4B2b password policy 使用 12–64 printable ASCII characters（`U+0020`–`U+007E`，包括普通半角空格）；不做 composition rule、trim、字符替换或 Unicode normalization。该决定是 V1 usability / compatibility trade-off，低于当前 NIST password-only 15-character minimum 的 residual risk 必须明确保留。
- M0-S4B2b offline blocklist 使用 pinned SecLists 2026.1 Top 250,000 prefix，经 12–64 printable ASCII exact filter 后生成 2,065 个 sorted binary SHA-256 fingerprints（baseline 66,080 bytes）；只在 registration / password change / reset 的 Argon2id 前检查，不进入 login path。
- M0-S4B2c registration failure 只在 `LocalRegistrationService` 进行 structured safe logging；expected rejection 不记 ERROR，unexpected failure 只记录 stage、exception type 与已有 correlation ID，禁止 email、raw password、fingerprint、verifier、SQL parameter、database exception message 或完整 cause chain。
- M0-S4B2c atomic registration Design 已确认：normalize / policy / Argon2id 在 transaction 外执行，独立 `LocalRegistrationPersistence` bean 的外层 transaction 原子写入 `app_user`、`auth_identity` 与 credential；database unique constraint 裁决 concurrent duplicate，失败请求整体 rollback。

## Completed Review

1. M0-S1 Spring Boot 与 Vue application skeleton；
2. M0-S1 backend/frontend 独立 build 与 startup；
3. `V1_SCOPE.md` v1.3 的 Product Audience、Success Model 与 Project Goal Priority；
4. M0-S2 Compose service、PostgreSQL 18 volume layout、loopback exposure 与 host/container port boundary；
5. M0-S2 pgvector availability 与 Flyway-owned installation boundary。
6. M0-S3 UUIDv7 identity schema、Language Profile ownership boundary 与 deletion constraints；
7. M0-S3 MyBatis Mapper XML persistence path、UUID TypeHandler 与 SQL injection safety boundary。
8. M0-S4A Spring Security boundary、trusted `UserContext` 调用链与 ownership-scoped access；
9. M0-S4A unauthenticated、owner、cross-user 与 request `userId` spoofing verification。
10. M0-S4B1 authentication identity / credential persistence、transaction / foreign key boundary、Review 与 Human Ownership Check。
11. M0-S4B2a versioned Argon2id hashing、verifier resource-parameter gate、Review 与 Human Ownership Check。
12. M0-S4B2b printable-ASCII password policy、pinned offline blocklist、deterministic asset generation、Review 与 Human Ownership Check。
13. M0-S4B2c atomic registration Scope、correctness、transaction、concurrent duplicate、safe logging、failure contract Diff Review 与 Human Ownership Check。

## Current Slice

```text
Selected slice: M0-S4B2c
Gate: READY_TO_COMMIT
Scope: APPROVED
Implementation: COMPLETE
Verification: COMPLETE — service tests, PostgreSQL atomicity / concurrent duplicate tests and full backend regression passed
Review: PASS — no blocking findings
Ownership: COMPLETE — user explained transaction placement, rollback, duplicate mapping and safe exception boundary
Production baseline: M0-S4B2c IMPLEMENTATION / VERIFICATION / REVIEW / OWNERSHIP COMPLETE
Later slices: Redis Session → CSRF / throttling / hash capacity → Self-hosted SINGLE_USER
```

## Next Action

等待人工 Commit Decision；commit 前不进入 Redis Session slice。

## Blockers

None. 本机端口 `5432` 已被其他服务占用，runtime verification 通过 `DATABASE_PORT=15432` 完成，配置 override 已验证。
