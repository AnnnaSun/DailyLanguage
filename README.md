# DailyLanguage

AI Language Tutor 与统一学习平台的后端仓库，当前正在按独立 slice 推进平台迁移。
当前 Phase、Gate、已批准范围和下一动作以 [PROJECT_STATUS](docs/planning/PROJECT_STATUS.md)
及其链接的 Feature 合同为准；本页只说明本地开发入口，不单独维护另一份进度。

## 环境要求

| 工具 | 项目约定 | 选择方式 |
| --- | --- | --- |
| JDK | Java 25；当前开发版本为 Temurin `25.0.4-tem` | 根目录 `.sdkmanrc`，通过 SDKMAN 在当前 shell 选择 |
| Maven | 3.9.12 | 根目录与 `server/` 各自的 Maven Wrapper；不要求手动安装 Maven |
| Node.js / npm | Node 24、npm 11 | `client/.nvmrc`、`client/package.json` 与 `package-lock.json` |
| Docker | 可运行 Docker Compose | Mac 使用 Docker Desktop；Windows 使用 Docker Desktop 并启用目标 WSL2 发行版集成 |

不因跨设备切换而升级依赖。Maven Wrapper 首次使用会下载指定 Maven，后续复用本机缓存；
缓存、依赖目录和 Docker 数据卷不随 Git 同步。

## Mac / Windows（WSL2）初始化

下面的命令使用 Bash / Zsh。在 Windows 上进入 WSL2 终端执行，并将代码 clone 到 WSL 的
Linux 文件系统（例如 `~/Developer/Projects/DailyLanguage`），使用 WSL 内的 JDK、Node 与 Git。
Mac 与 WSL 分别安装 [SDKMAN](https://sdkman.io/install) 和 [nvm](https://github.com/nvm-sh/nvm#installing-and-updating)，
不要复制 Mac 的安装目录或在 WSL 构建中混用 Windows 的 Java / Node。

在仓库根目录选择 JDK；首次缺少所需版本时先执行 `sdk env install`：

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk env install
sdk env
java -version
./mvnw -v
```

本说明使用显式 `sdk env` 选择 JDK，不要求开启 SDKMAN 自动环境切换；每次新开终端进入项目后执行该命令。
IDE 的 Project SDK 与 Maven JDK 也应选择相同版本，避免 IDE 与终端使用不同 Java。
根目录的 `mvnw` 和 `mvnw.cmd` 原样复用已有 `server/` Wrapper；换行规则由 `.gitattributes` 管理。
Windows 原生终端可使用 `mvnw.cmd`，但本页环境变量和启动示例按 Mac / WSL shell 编写。

在已加载 nvm 的 shell 中安装前端依赖：

```bash
cd client
nvm install
nvm use
node --version
npm --version
npm ci
cd ..
```

确认 Node 主版本为 24、npm 主版本为 11。新设备分别准备 GitHub 认证及本机私有配置；
不把 `.env`、凭据、数据库或 SDK 缓存复制进仓库。

## 选择后端入口

| 工作范围 | 构建入口 | 环境与验证说明 |
| --- | --- | --- |
| 新平台 Identity | 仓库根目录 `pom.xml` 与 `./mvnw`；模块为 `services/identity` | [Identity 本地环境](deploy/local/identity/README.md)、[当前 Feature 合同](docs/features/IDENTITY.md) |
| 旧 Language | `server/pom.xml` 与 `server/mvnw` | 下方旧 Language 的基础设施、启动和测试说明 |
| 现有前端 | `client/` | 使用该目录的 `.nvmrc` 与 npm lockfile |

根 POM 当前只聚合 Identity，不包含旧 `server/`。例如从仓库根目录执行
`./mvnw -pl services/identity -am validate`，只验证新平台 POM；旧 Language 验证仍需进入 `server/`。
具体测试选择按当前 slice 合同执行，不能以初始化成功代替功能验收，也不因环境补充而跳过 Human Core Gate。

## 旧 Language：本地基础设施

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

## 旧 Language：后端

Authentication endpoint、request 参数以及 `400/401/403/404/409/429/503` response `code`
快速查询见 [Authentication API Contract](docs/features/AUTHENTICATION_API.md)。

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

旧 Language 默认 test suite 不要求本地基础设施。需要验证 Flyway、Repository 与真实
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

在仓库根目录停止旧 Language 基础设施，但保留 Docker named volumes（命名数据卷）：

```bash
docker compose down
```

上述启动说明不表示旧 Language 的所有功能或新平台迁移已经完成。
实际实现、验证局限和当前 Gate 仍以 [PROJECT_STATUS](docs/planning/PROJECT_STATUS.md) 及对应 Feature 为准。
