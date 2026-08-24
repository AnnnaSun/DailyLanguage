# DailyLanguage

AI Language Tutor 的 V1 工程目前处于 `M0-S3` User / Language Profile
persistence identity 阶段。

## 环境要求

- Java 25
- Node.js 24
- Docker Desktop（包含 Docker Compose）

## 本地基础设施

PostgreSQL（包含 pgvector）和 Redis 运行在本地 Docker 容器中。Spring Boot
直接运行在本机，通过仅绑定到本机回环地址（loopback）的端口连接这些容器。

仓库中的默认连接信息只用于本地开发。需要自定义时，在启动容器前将
`.env.example` 复制为 `.env`。如果后端也需要读取自定义值，应在启动
Spring Boot 前将相同变量导入当前 shell。

例如 PostgreSQL 端口 `5432` 已被占用时，可以在 `.env` 中设置
`DATABASE_PORT=15432`，然后导入变量：

```bash
set -a
source .env
set +a
```

启动并检查容器：

```bash
docker compose config
docker compose up -d
docker compose ps
```

无需安装本地客户端，可直接进入容器查询 PostgreSQL：

```bash
docker compose exec postgres psql -U daily_language -d daily_language
```

常用 `psql` 检查：

```sql
SELECT version();
SELECT name, default_version, installed_version
FROM pg_available_extensions
WHERE name = 'vector';
```

`M0-S3` 开始由 Flyway 统一管理 `CREATE EXTENSION` 和数据库结构迁移。
应用连接 PostgreSQL 后会自动执行 `server/src/main/resources/db/migration` 下
尚未应用的 migration。当前初始 migration 会启用 pgvector，并创建
`app_user` 与 `language_profile` identity tables。

Persistence access 使用 MyBatis-Plus starter 与 MyBatis Mapper XML。Runtime query /
DML statement 集中在 `server/src/main/resources/mapper`，schema DDL 仍由 Flyway
migration 管理；所有变量值必须通过 `#{}` 绑定为 `PreparedStatement` 参数。禁止
`${}` raw substitution、Java SQL annotation / SQL 字符串，以及直接接收客户端提供的
column、order 或其他 SQL fragment。未来确有 dynamic identifier 需求时，必须先转换为
backend-defined allowlist value。

无需安装 GUI 或本地 CLI，可直接进入容器查询 Redis：

```bash
docker compose exec redis redis-cli
```

常用 Redis 检查：

```text
PING
DBSIZE
SCAN 0
```

## 后端

```bash
cd server
./mvnw test
./mvnw spring-boot:run
```

Session cookie 默认启用 `Secure`，防止 Hosted HTTPS deployment 意外下发可通过明文 HTTP
传输的认证 cookie。本地直接使用 HTTP 启动后端时，必须显式覆盖：

```bash
SESSION_COOKIE_SECURE=false ./mvnw spring-boot:run
```

该 override 只用于 local HTTP development，Hosted deployment 不得关闭 `Secure`。

默认 test suite 不要求本地基础设施。需要验证 Flyway、Repository 与真实
PostgreSQL constraints 时，先启动 PostgreSQL，再显式启用 database tests：

```bash
cd server
RUN_DATABASE_TESTS=true ./mvnw test
```

如果 PostgreSQL 使用了非默认端口，应同时传入相同的 `DATABASE_PORT`。

需要验证 Redis-backed Session persistence、JSON round-trip 与 namespace 时，先启动 Redis，
再显式启用 Redis integration tests：

```bash
cd server
RUN_REDIS_TESTS=true ./mvnw -Dtest=RedisSessionIntegrationTests test
```

如果 Redis 使用了非默认端口，应同时传入相同的 `REDIS_PORT`。

Redis unavailable fail-closed test 会故意连接不可用的 local port，因此与正常 Redis test
分开显式执行：

```bash
cd server
RUN_REDIS_UNAVAILABLE_TESTS=true ./mvnw -Dtest=RedisSessionUnavailableIntegrationTests test
```

基础设施启动后，通过以下健康检查接口验证两个连接：

```bash
curl http://localhost:8080/actuator/health
```

## 前端

```bash
cd client
npm ci
npm run build
npm run dev
```

停止本地基础设施但保留 Docker named volumes（命名数据卷）：

```bash
docker compose down
```

`M0-S3` 只提供 User / Language Profile persistence identity、Repository 与
ownership-scoped query boundary，不包含 Authentication / UserContext、完整
Language Profile、cache behavior、REST API 或 AI behavior。
