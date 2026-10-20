# Browser Authentication Flow

- Document Status: `IMPLEMENTED`
- Feature / Slice: `M1-S11-B`
- Last Verified: `2026-10-15`
- Entry: `App.vue onMounted / submitLogin / submitLogout / checkConnection`

## 1. Behavior Boundary

最小 Vue 页面接通已有 current-user、login、logout；不新增 authentication rule 或 backend API。
没有 registration UI、Language Profile / Practice / Provider / Credential UI。
本地 Vite `/api` 同源代理到 `127.0.0.1:8080`；Hosted 同源 ingress 不在此 slice。
启动方式：后端使用 README 的既有 local HTTP override `SESSION_COOKIE_SECURE=false`；
前端在 `client` 执行 `npm run dev`。Hosted 保持 HTTPS / Secure cookie。

## 2. Main Call Chain

```mermaid
sequenceDiagram
    actor User
    participant Vue as App.vue
    participant Client as api.ts
    participant Java as Spring Security / CurrentUserController
    participant Redis as Redis Session
    Vue->>Client: onMounted / refreshIdentity
    Client->>Java: GET /api/auth/me (same-origin cookie)
    Java->>Redis: Restore Session (registered-user mode)
    Java-->>Client: userId / 401 UNAUTHENTICATED / failure
    Client-->>Vue: confirmed identity / null / ApiError
    User->>Vue: Submit login or logout
    Vue->>Vue: pending=true; clear password ref for login
    Vue->>Client: login / logout
    Client->>Java: GET /api/auth/registration
    Java-->>Client: capability + SPA XSRF-TOKEN cookie
    Client->>Java: POST with X-XSRF-TOKEN + cookie
    Java->>Redis: Create / rotate or invalidate Session
    Java-->>Client: 204 / rejection / unavailable
    alt Successful write
        Vue->>Client: refreshIdentity
        Client->>Java: GET /api/auth/me
        Java-->>Vue: Current server identity
    else Failure or timeout
        Vue->>Vue: safe error; no automatic write retry
    end
```

login body 是既有 form-urlencoded email/password；logout 没有 Credential body。
single_user mode 每次由 `SingleUserAuthenticationFilter` 建立 identity，不依赖认证 Session；
登出重查仍有身份时显示“当前环境仍提供已认证身份”。

## 3. State and Authority

- Vue 只持有展示状态、pending、表单与最近一次 `/me` 结果；没有 storage / log / Trace 写入。
- Java 保留 authentication / authorization / CSRF / rate-limit authority，Redis 保存 Session。
- app 不读取 HttpOnly SESSION cookie；浏览器自动携带它。可读 XSRF cookie 每次写入前重新获取。
- UI password ref 在调用 login 前清空；实际请求所需密码仅短暂存在内存。浏览器可能自动填充，
  本实现不修改 browser password-manager 设置，不能保证其填充后的 DOM 始终为空。
- 不查询或改变 `languageProfileId`、学习状态、Planner / Practice / Evaluator；不调用 LLM 或 Model Gateway。

## 4. State Transition

`checking → ready(user | null)` 只在合法 `/me` 结果后发生；失败为 `unavailable`。
login / logout 204 本身不决定展示身份，必须重查。invalid credentials 保持 anonymous ready，
允许用户修改输入；其他写失败清除旧身份展示，进入 unavailable，用户可只读重查。
pending guard 防止事件重复进入，controls 同时禁用；finally 恢复可操作状态。

## 5. Failure / Rejection Paths

- 仅 `401 UNAUTHENTICATED` 映射 anonymous；503、非协议 401、非法 success body 不能解释成未登录。
- 缺失 / 非法编码 CSRF cookie 阻止 POST。非 JSON 403 也保留安全错误，不暴露内部文本。
- 后端 CSRF 拒绝经 sendError(403) 进入 Tomcat ERROR /error；SecurityConfiguration 允许内部 ERROR
  分发通过授权检查，让错误处理保留 403。普通 REQUEST /error 仍要求认证，CSRF 校验与安全过滤器链仍保留。
- 每个 fetch 使用 10s AbortSignal；网络错误 / timeout 不暴露 exception detail、不自动 retry。
  write 可能已发生，因此提示结果未知，重查不重放 login / logout。
- 429 显示稍后重试；不自动等待或循环提交。未实现 countdown 或自动恢复策略。
- 重查时 Session 丢失返回匿名；服务失败返回 unavailable，不能用浏览器旧状态维持成功假象。

## 6. Verification Evidence

Fresh 2026-10-13：new `client/tests/api.test.mjs` 16/16；`npm run build` PASS；existing
AuthenticationHttpContractTests 14 / LocalRegistrationHttpContractTests 13 / isolated
RedisAuthenticationSessionIntegrationTests 2，共 29/29，0 skipped。

IAB + 实际 Spring Boot / 临时 PostgreSQL / Redis：错误密码、登录、刷新、登出及刷新、清空临时
Redis 模拟 Session 丢失、真实 429、暂停 Redis 的 pending / 10s timeout / 恢复重查，以及 single_user
自动身份和登出重查均验证。真实登录 / 登出完成证明浏览器 cookie + CSRF header 链路接通。
Hosted HTTPS、实际 24h TTL、live Provider、全量 server suite 与后续学习 UI 未验证。

Historical（2026-10-13）真实 HTTP 负例差异：single_user 模式带 XSRF cookie、缺 X-XSRF-TOKEN 的 login / logout 实际返回
`401 UNAUTHENTICATED`，与既有 MockMvc / API 文档的 403 不同；最初 403 assertion 失败，不计作 PASS。
2026-10-15 已确认 Root Cause：容器 ERROR /error 被再次鉴权，把原始 403 覆盖成 401；用户批准仅放行
内部 ERROR 分发。client 对其他 write-side 401 仍抛出 ApiError，App 进入 unavailable 并只读重查。

Fresh 2026-10-15 correction regression：AuthenticationErrorDispatchHttpTests 14/14（真实本机 Tomcat / HTTP、
production Security / Controller、外部依赖替身、容器内存 Session，无 PostgreSQL / Redis）；既有
AuthenticationHttpContract 14 + SpaCsrfHttpContract 3 + LocalRegistrationHttpContract 13，共 44/44 PASS。
新测试在修复前两个 missing-CSRF case 为 RED；最终确认两种模式的 missing / wrong token 为 403、
普通 REQUEST /error 与 /me 为 401、合法登录登出正常，非法登出不注销 Session。
本次未重跑 browser / Redis persistence / Hosted HTTPS；详细 evidence 与中间失败记录见 M1 Feature §19.4。

## 7. Source References

- `client/src/App.vue`：refreshIdentity / checkConnection / submitLogin / submitLogout。
- `client/src/api.ts`：request / getCurrentUser / csrfHeaders / login / logout。
- `client/vite.config.ts`：loopback dev proxy。
- `server/src/main/java/com/dailylanguage/security/config/SecurityConfiguration.java`：SPA CSRF / form login / logout。
- `server/src/main/java/com/dailylanguage/security/api/CurrentUserController.java`：trusted current identity。
- `server/src/main/java/com/dailylanguage/security/infrastructure/SingleUserAuthenticationFilter.java`：single_user identity。
- `server/src/test/java/com/dailylanguage/security/infrastructure/AuthenticationErrorDispatchHttpTests.java`：真实容器 ERROR 分发与权限边界回归。
- `docs/features/AUTHENTICATION_API.md`、`docs/features/M1_MINIMUM_TEXT_PRACTICE.md` §19：API / slice contract。
