# ADR-0002: Authentication, Password, and Session Security

- Status: ACCEPTED
- Date: 2026-08-22
- Scope: M0-S4 authentication and `UserContext` foundation
- Implementation: S4A–S4B2b READY_TO_COMMIT; S4B2c–S4D NOT STARTED

## Context

M0-S3 已建立 `app_user`、`language_profile` 与
`languageProfileId + userId` ownership-scoped persistence query，但 request 尚无可信
identity source。Hosted V1 需要正式 Account/Auth，Self-hosted 需要简化模式；两种部署下
业务层必须统一使用 `UserContext`，不得信任 frontend、LLM 或 Tool 参数中的 `userId`。

Local password verification 还引入两类风险：

- database 泄露后进行 offline password guessing；
- 攻击者并发触发昂贵 password hash，耗尽 CPU、memory 或 request capacity。

因此 Authentication mechanism、credential persistence、Session、authorization 与
password-hash resource protection 必须作为一个 Security Architecture boundary 设计，
但通过多个小 implementation slice 交付。

## Decision

V1 采用：

```text
Spring Security
  + local email/password identity
  + Argon2id password verifier
  + PostgreSQL credential persistence
  + Redis-backed server-side HTTP Session
  + cookie and CSRF protection
  + authenticated UserContext authorization
```

不在 V1 当前 slice 使用 browser-stored JWT。Sign in with Apple、phone OTP、Passkey、
其他 OIDC Provider 与 Account Linking 由 `IDEA-006` 保留，不进入 M0-S4 implementation。

## Identity and Authorization Boundary

`app_user.id` 是内部稳定 User identity。Authentication channel 不等于 Domain User：

```text
app_user
    ↓ one-to-many
auth_identity
    ↓ local channel only
local_password_credential
```

`auth_identity` 以 `(provider, provider_subject)` 建立唯一 identity；当前只实现
`LOCAL_EMAIL`。Password verifier 由 local credential 单独持有，避免把 `app_user`
永久等同于 email、phone number 或 external Provider email。

认证成功后建立：

```java
UserContext(UUID userId)
```

业务请求必须使用：

```text
Spring Security Authentication
        ↓
authenticated internal userId
        ↓
UserContext
        ↓
languageProfileId + userId scoped query
```

禁止使用 `X-User-Id`、request body `userId` 或 LLM / Tool-provided `userId` 作为可信身份。
访问不存在或不属于当前用户的 Language Profile 统一表现为 not found，避免 resource
enumeration。

### S4B1 Approved Persistence Design

Local email identity 使用确定性 V1 normalization：去除首尾空格、限制为 ASCII email、
最长 254 characters，并以 `Locale.ROOT` 转为小写；不执行 Gmail-specific dot / plus
canonicalization。Internationalized Email 不进入 M0-S4。

`auth_identity` 保存：

- UUIDv7 `id`；
- `user_id`，引用稳定的 `app_user.id`；
- `provider`，M0-S4 database constraint 只允许 `LOCAL_EMAIL`；
- normalized `provider_subject`；
- `created_at`；
- `(provider, provider_subject)` unique constraint。

不增加 `(user_id, provider)` uniqueness，以保留一个 User 关联多个 authentication identities
的长期模型。`auth_identity.user_id` 使用 `ON DELETE RESTRICT`。

`local_password_credential` 保存：

- `auth_identity_id`，同时作为 primary key 与 foreign key；
- `password_verifier VARCHAR(512)`；
- `created_at` 与 `updated_at`。

Credential 对 identity 使用 `ON DELETE CASCADE`，因为 verifier 没有脱离 identity 独立存在的
语义。Algorithm version 不另设 column，而是由 self-describing verifier prefix（例如
`{argon2id-v1}`）承载，避免 version column 与 encoded verifier 不一致。

S4B1 只接收 encoded verifier，不接收 raw password，并保证 identity + credential persistence
自身原子化。S4B2 registration service 再以外层 `@Transactional` boundary 覆盖：

```text
create app_user
    +
create auth_identity
    +
create local_password_credential
```

## Password Storage

PostgreSQL 只保存 versioned encoded password verifier，不保存 plaintext password 或
可逆 encrypted password。

当前 encoding version：

```text
algorithm:    Argon2id
version:      argon2id-v1
memory:       19 MiB
iterations:   2
parallelism:  1
salt:         16 random bytes
hash:         32 bytes
```

Security parameters 在 code 中版本化，不作为可任意调低的 runtime configuration。
未来参数升级通过新增 encoding version 完成；成功登录后可以逐步 re-hash，不能静默改变
`argon2id-v1` 的含义。

使用 Spring Security `PasswordEncoder` / `DelegatingPasswordEncoder` 与受支持的
Argon2id implementation，不自行实现 password hashing algorithm。Raw password 只在
registration/login request 的最短必要调用链中存在，不进入 PostgreSQL、Redis、Log、
Trace、exception、metrics 或 Domain Event。

Password policy 至少包含：

- password-only authentication 长度为 12–64 characters；
- 只接受 printable ASCII `U+0020`–`U+007E`，包括普通半角空格；不接受 Unicode、
  control characters、Tab 或换行；
- 不 trim、不自动删除或替换字符；ASCII 输入直接进入 policy / blocklist / hash 或 match；
- 支持 password manager 与 paste/autofill；
- 不使用强制 character composition rule 或无理由 periodic rotation；
- registration 对 common / compromised password blocklist 做检查；
- frontend validation 只负责 UX，backend policy 是 authoritative boundary。

当前 NIST SP 800-63B 对 password-only authentication 采用 15-character minimum，并建议接受
Unicode。项目为降低 V1 输入一致性与用户记忆负担，明确选择 12-character minimum 与
printable-ASCII-only policy。该决定是产品与兼容性 trade-off，不视为等价的 security
enhancement；其 residual risk 由 blocklist、Argon2id、Rate Limit 与 password-hash concurrency
gate 共同降低。Printable ASCII 本身是 NFC-stable，因此本 policy 不执行 Unicode
normalization。

Offline blocklist 使用固定、可复现的 source artifact：

- source 是 SecLists `2026.1` release / commit
  `190c6f7bd58c847ceadfe57d9853592737f059e8` 中
  `Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt` 的前
  250,000 个 ranked entries；
- build-time generation 只保留 12–64 printable ASCII entries，并按完整字符串进行
  case-sensitive exact deduplication；当前固定 source 产生 2,065 个 baseline entries；
- runtime artifact 保存按字节排序的 32-byte SHA-256 fingerprints，不保存原始 million-entry
  source；baseline payload 是 66,080 bytes；
- registration、password change 与 password reset 在 Argon2id 前检查完整 candidate；login
  path 不执行 blocklist check；
- 不进行 substring、case folding、character replacement 或其他 heuristic transformation；
- service name 等少量 static context-specific values 进入 generated artifact；candidate 与
  login email 或 email local part 完全相同时由 policy 动态拒绝；
- 命中时只返回可操作的通用原因，不记录 candidate、fingerprint 或匹配 entry。

Blocklist fingerprint 只用于公开 weak-password set membership，不是 credential verifier，
不能替代 Argon2id。Source version、source checksum、filtered entry count 与 generated artifact
checksum 必须由 deterministic test 固定，避免 dependency update 静默改变 password policy。

M0-S4 不自行增加 pepper wrapper。Pepper 需要独立 Secret Manager / HSM、version、
rotation、backup、loss recovery 与 forced-reset 策略；是否在 Hosted production 使用
managed pepper 留到 M6 Security Gate 决定，不能用未经管理的 `.env` secret 冒充完整方案。

## Session and Browser Security

Hosted V1 使用 Redis-backed server-side HTTP Session：

- browser 只持有 opaque Session cookie；
- Hosted cookie 使用 `HttpOnly`、`Secure`、适当 `SameSite` 与最小 Path/Domain scope；
- login success 触发 session ID rotation；
- logout invalidates server-side Session 并清理 cookie；
- unsafe HTTP method 保持 CSRF protection；
- Vue / PWA 与 Backend 默认通过 same-site deployment / reverse proxy 通信；
- Redis Session 不保存 raw password、password verifier 或 BYOK API Key。

Authentication 使用 Spring Security filter / provider lifecycle，不在 Controller 手工比较
password 并自行拼装 SecurityContext。

## Password-hash Resource Protection

Argon2id 是 CPU / memory-bound workload。M0-S4 必须同时实现：

```text
cheap request validation
    ↓
Redis-backed IP / identifier / account rate limit
    ↓
global password-hash concurrency gate
    ↓
Argon2id encode / matches
```

Rate-limited request 必须在执行 Argon2id 前返回。Global concurrency gate 使用有界 permit
并 fail fast；禁止 unbounded queue。Registration `encode()`、known-account `matches()` 与
用于防止 account enumeration 的 unknown-account dummy verification 都受同一全局容量边界。

Policy rate limit 返回 `429 Too Many Requests`；global hash capacity exhausted 返回
`503 Service Unavailable`。Redis security state unavailable 时 Hosted authentication
fail closed，不允许静默绕过 Rate Limit。

## Security Parameters vs Capacity Parameters

Argon2id algorithm parameters 是现在确认的 Security Baseline。Password-hash concurrency
是 hardware-dependent Capacity Parameter：

```text
DEV / TEST default:          1
Self-hosted safe default:    1
Hosted:                      explicit configuration required
```

`maxConcurrentHashes` 只能由 trusted server configuration 提供，不能来自 request。Hosted
未显式配置时 startup fail；不能把开发机结果当作 production capacity conclusion。

M0-S4 通过受限 Container profile 验证机制与 provisional capacity。M6 必须在真实或等价
Hosted hardware 上执行 Argon2 benchmark、open-model concurrent login load、mixed workload、
soak 与 recovery test，然后记录：

- hardware / Container / JVM profile；
- Argon2 encoding version；
- configured max concurrency；
- p95 / p99 latency；
- CPU、memory、GC 与 recovery evidence。

只有完成上述验证，Hosted concurrency 才从 `PROVISIONAL` 变为 `CONFIRMED`。

## Hosted and Self-hosted

Hosted 使用正式 Account/Auth、Redis Session、HTTPS 与 explicit hash concurrency config。

Self-hosted `SINGLE_USER` 通过 server-side bootstrap 建立一个 User，public registration
默认关闭；仍经过 Spring Security 并生成相同 `UserContext`。不得以关闭复杂 Auth 为理由
引入 request-provided identity。Hosted / Self-hosted 共享后续 Domain Service。

## Alternatives

### Browser-stored JWT

JWT 适合独立 mobile client、third-party API 或跨服务 authorization，但当前 same-site
Vue / Spring Boot V1 会额外引入 token storage、refresh rotation、logout / revocation、
signing-key rotation 与 stale claim 成本。Redis Session 能提供即时 invalidation，且不把
credential token 暴露给 frontend JavaScript。

### Reversible Password Encryption

Encryption key 泄露会恢复全部 password，且登录验证不需要取回原文，因此拒绝。

### External Identity Provider Only

可以避免本系统保存 local password verifier，但会把 Hosted / Self-hosted 的基本登录依赖
外部服务。External Provider 保留为后续可绑定 identity，不作为 V1 唯一登录方式。

### Unbounded Argon2 Execution

只依赖 Servlet thread pool 或 IP rate limit 无法抵御 distributed identifiers / sources，
因此必须有 global password-hash concurrency gate。

## Consequences

- 新增 Spring Security、Spring Session Redis、Argon2 provider 与相关 test dependency；
- 新增 authentication identity / local credential schema 与 public Auth API；
- Redis 成为 Hosted Session 与 authentication throttling 的 runtime dependency，但不成为
  long-term learning state authority；
- Password hashing 增加 login latency 与 CPU/memory cost，换取 offline cracking resistance；
- capacity saturation 时合法 login 可能暂时收到 `429` / `503`，但系统资源保持有界；
- Password authentication 仍不具备 phishing resistance，MFA / Passkey 属于后续增强；
- password reset / recovery、email verification 与 multi-channel Account Linking 需要独立
  Product / Security Scope；
- Architecture Docs 只在相应 implementation slice 完成后更新为真实当前实现。

## Verification Obligations

M0-S4 必须覆盖：

- unauthenticated、owner 与 cross-user request boundary；
- password hash uniqueness、correct/wrong match、malformed verifier fail-closed 与 upgrade；
- registration atomicity 与 credential uniqueness；
- Session creation、rotation、expiry、logout invalidation 与 CSRF；
- Rate Limit 在 Argon2 前生效；
- active hash count 不超过 configured capacity，exception 后无 permit leak；
- capacity saturation fail fast、无 unbounded queue、无 password leakage；
- restricted Container 下的 provisional benchmark、mixed workload 与 recovery evidence；
- Hosted / Self-hosted 均产生同一 `UserContext` contract。

## References

- [NIST SP 800-63B — Authentication and Authenticator Management](https://pages.nist.gov/800-63-4/sp800-63b.html)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Spring Security Password Storage](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html)
- [Spring Security Session Management](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/session-management.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html)
- [Spring Session Configuration](https://docs.spring.io/spring-session/reference/configurations.html)
- [k6 Constant Arrival Rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)
