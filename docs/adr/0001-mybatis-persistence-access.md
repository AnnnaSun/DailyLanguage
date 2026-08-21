# ADR-0001: MyBatis Persistence Access

- Status: ACCEPTED
- Date: 2026-08-22
- Scope: M0-S3 persistence foundation

## Context

项目需要兼顾长期数据管理、常规 CRUD、未来复杂 PostgreSQL / pgvector query，
同时不在 Java code 中直接维护 SQL。Persistence query 还必须建立明确的 SQL
injection 防线。

## Decision

采用以下组合：

```text
PostgreSQL + pgvector
        ↓
      Flyway
        ↓
MyBatis-Plus starter
        ↓
MyBatis Mapper XML
        ↓
Repository / Service transaction boundary
```

- Flyway 是 schema authority；
- 常规 persistence 能力使用 MyBatis-Plus stack；
- 自定义及复杂 query 放入 Mapper XML；
- Repository / Service 不暴露 MyBatis query wrapper；
- database-generated UUIDv7 通过 `INSERT ... RETURNING` 保留；
- 当前不启用 global logical delete，删除 lifecycle 等 IDEA-004 决策；
- SQL 安全规则以 `#{}` parameter binding 和 backend allowlist 为基础；
- 禁止 `${}`、Java SQL annotation / SQL string、外部 SQL fragment，以及
  `last`、`apply`、`SqlRunner` 等绕过边界的入口。

## Alternatives

### Spring Data JPA / Hibernate

常规 entity lifecycle 和 CRUD 较方便，但未来复杂 PostgreSQL / pgvector query
仍可能需要 native SQL，并增加 persistence context、mapping 与 query path 的认知成本。

### Spring JDBC

依赖少、执行路径直接，但 SQL 会进入 Java code，且大量复杂 query 的组织与 mapping
成本会逐渐上升。

### Plain MyBatis

复杂 query 表达清晰，但常规 CRUD 需要更多重复 Mapper statement。MyBatis-Plus 在
不改变 MyBatis XML 复杂查询能力的前提下，为后续常规 CRUD 提供统一能力。

## Consequences

- SQL 可集中 review，并与 Java domain code 分离；
- 复杂 query 不受 ORM query abstraction 限制；
- 团队需要维护 Mapper XML、result mapping 与必要的 database-specific TypeHandler；
- MyBatis 不自动消除 SQL injection 风险，因此 static safety test、parameter binding
  和 backend allowlist 是长期约束。
