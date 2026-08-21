# DailyLanguage

AI Language Tutor 的 V1 工程目前处于 `M0-S2` 本地基础设施基线阶段。

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

`M0-S2` 只验证 pgvector 扩展可用，不启用该扩展。从 `M0-S3` 开始，
`CREATE EXTENSION` 和数据库结构迁移统一由 Flyway 管理。

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

`M0-S2` 只提供基础设施和连接边界，不包含 Domain table（领域数据表）、
Repository、cache behavior（缓存行为）、Security 或 AI behavior。
