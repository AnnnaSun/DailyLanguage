# Authentication API Contract

本文是 local registration、login、logout 和 current-user API 的快速查询表。正式 Security
decision 仍以 [ADR-0002](../adr/0002-authentication-password-session-security.md) 为准。

## Response error format

应用主动返回的 Authentication / Registration error 使用稳定的 machine-readable `code`：

```json
{
  "code": "INVALID_CREDENTIALS"
}
```

客户端应根据 `code` 选择 UI 文案，不应依赖 Java exception message、Backend log 或英文错误文字。
CSRF rejection 和 Single-user mode 隐藏 login endpoint 属于 Security Filter response，目前不保证
返回上述 JSON 结构。

## CSRF request requirement

所有 `POST` 请求都必须同时具备：

- HTTP 客户端保存并自动发送 `XSRF-TOKEN` cookie；
- request header 手动发送 `X-XSRF-TOKEN: <cookie 中的 token>`。

Cookie 的 `Path`、`SameSite` 和 `Secure` 是 response cookie attribute，不需要作为 request header
参数手动提交。缺失或错误的 token 返回 `403`，并在 Rate Limit、Controller 和 Argon2 前停止。

## `POST /api/auth/login`

Request：

```http
Content-Type: application/x-www-form-urlencoded

email=learner%40example.com&password=...
```

| HTTP status | Response `code` | 含义与触发阶段 |
| --- | --- | --- |
| `204 No Content` | — | 登录成功；创建或 rotation `SESSION` cookie |
| `401 Unauthorized` | `INVALID_CREDENTIALS` | email/password 缺失、格式无效、未知账号或密码错误；这些情况故意使用同一结果，防止 account enumeration |
| `403 Forbidden` | 不保证 JSON code | CSRF cookie/header 缺失或不匹配；请求不会进入 Login Rate Limit 或 Argon2 |
| `404 Not Found` | 不保证 JSON code | Single-user mode 下隐藏 login endpoint；有效 CSRF 请求在进入 Rate Limit 和 Argon2 前停止 |
| `429 Too Many Requests` | `TOO_MANY_LOGIN_ATTEMPTS` | Login IP/email policy 超限；response 包含秒数形式的 `Retry-After`，请求不会进入 Argon2 |
| `503 Service Unavailable` | `AUTHENTICATION_UNAVAILABLE` | Argon2 concurrency capacity exhausted，或 Login Rate Limit、authentication persistence、Session Redis 等 infrastructure unavailable；不泄露底层详情 |

默认 Login Rate Limit：

- 同一 client address：`20` 次 / `5` 分钟；
- 同一 normalized email：`5` 次 / `5` 分钟。

## `GET /api/auth/registration`

用于读取当前 registration capability，不创建用户：

```json
{"state":"PUBLIC"}
```

或：

```json
{"state":"DISABLED"}
```

## `POST /api/auth/registration`

Request：

```http
Content-Type: application/x-www-form-urlencoded

email=learner%40example.com&password=...
```

| HTTP status | Response `code` | 含义与触发阶段 |
| --- | --- | --- |
| `204 No Content` | — | 注册成功；只创建用户和 local identity，不创建登录 Session |
| `400 Bad Request` | `INVALID_EMAIL` | email 不满足 local registration contract |
| `400 Bad Request` | `INVALID_PASSWORD_LENGTH` | password 长度不符合 policy |
| `400 Bad Request` | `INVALID_PASSWORD_CHARACTER` | password 包含不允许的字符 |
| `400 Bad Request` | `COMMON_OR_COMPROMISED_PASSWORD` | password 命中当前 common/compromised password policy |
| `403 Forbidden` | `REGISTRATION_DISABLED` | `REGISTRATION_ENABLED=false`；在 Registration Rate Limit 和业务注册前停止 |
| `403 Forbidden` | 不保证 JSON code | CSRF cookie/header 缺失或不匹配；在 Rate Limit 和 Controller 前停止 |
| `409 Conflict` | `IDENTITY_UNAVAILABLE` | normalized local email 已被占用；不回显具体 identity |
| `429 Too Many Requests` | `TOO_MANY_REGISTRATION_ATTEMPTS` | Registration IP/email policy 超限；response 包含秒数形式的 `Retry-After` |
| `503 Service Unavailable` | `REGISTRATION_UNAVAILABLE` | Registration Rate Limit Redis、Argon2 capacity 或 persistence 等 infrastructure unavailable；不泄露底层详情 |

默认 Registration Rate Limit：

- 同一 client address：`5` 次 / `1` 小时；
- 同一 normalized email：`3` 次 / `1` 小时。

## Session endpoints

| Endpoint | Success | Expected failure |
| --- | --- | --- |
| `GET /api/auth/me` | `200 {"userId":"<uuid>"}` | 未登录或 Session 过期 → `401 {"code":"UNAUTHENTICATED"}`；Session Redis unavailable → `503 {"code":"AUTHENTICATION_UNAVAILABLE"}` |
| `POST /api/auth/logout` | `204 No Content`；失效当前 Redis Session 并清除 cookies | CSRF rejection → `403`；Session Redis unavailable → `503 {"code":"AUTHENTICATION_UNAVAILABLE"}` |

## Status classification

| Status family | 如何理解 |
| --- | --- |
| `400` | Request 内容不满足 registration policy；修改输入后再提交 |
| `401` | 当前 credential 或 Session 没有建立 authenticated identity；不是 Rate Limit |
| `403` | 当前请求被 CSRF、registration capability 等 Security / Policy boundary 阻止 |
| `404` | Single-user mode 主动隐藏不适用的 login endpoint |
| `409` | 请求格式有效，但 local identity 已存在 |
| `429` | Policy Rate Limit 拒绝；读取 `Retry-After` 后再尝试，Argon2 没有执行 |
| `503` | 当前 Security / Infrastructure capacity 不可用；可能需要稍后重试，不能解释成密码错误 |

`401`、`429` 和 `503` 不可互换：

```text
401 → login credential 被拒绝，或当前请求没有 authenticated Session
429 → Rate Limit 在 credential/hash 前拒绝
503 → infrastructure 或 Argon2 capacity 当前不可用，无法完成正常 authentication decision
```
