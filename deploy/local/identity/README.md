# Identity 本地隔离验证环境

此目录仅准备 Identity 的本地学习环境，不是生产部署，也不操作旧 Language / PersonalNote 实例。
独立 PostgreSQL 18 容器使用端口 `127.0.0.1:55441` 和本项目专用新卷，不加载 pgvector extension。
隔离实例是验证安排，不改变 C24 目标平台共用实例、每服务独立库与账号的决定。

## 凭据与启动

首次使用时，将本目录的 `.env.example` 复制到仓库外的私有文件，填写两个不同的随机密码，文件权限设为 `600`。
例如在 Mac / WSL 中保存在 `$HOME/.config/dailylanguage/identity.env`，父目录按需创建。
已有环境应直接使用原来的凭据文件，不因路径示例变化覆盖、重置密码或迁移数据。
临时目录中的文件不应作为凭据的唯一长期备份；本次文档更新不创建、移动或读取实际凭据。
后续继续使用同一文件：PostgreSQL 初始化 SQL 只对空的新卷执行，修改文件不会自动修改已有账号密码。
文件丢失时先恢复凭据或明确处理账号，不默认删除卷、清库或重新初始化。

在仓库根目录，先按根 README 选择 JDK，再将 `IDENTITY_ENV_FILE` 设为本机实际私有文件路径。
下面的示例适用于 Mac / WSL 的 Bash / Zsh；每个新终端都需重新设置路径并加载变量：

```bash
IDENTITY_ENV_FILE="$HOME/.config/dailylanguage/identity.env"
chmod 600 "$IDENTITY_ENV_FILE"
docker compose --env-file "$IDENTITY_ENV_FILE" -f deploy/local/identity/compose.yaml up -d --wait
set -a
source "$IDENTITY_ENV_FILE"
set +a
export IDENTITY_REDIS_HOST=127.0.0.1
export IDENTITY_REDIS_PORT=55442
```

`identity_test_admin` 仅用于隔离实例初始化；Identity 使用 `identity_app`，该角色不是超级用户，
没有 CREATEDB / CREATEROLE。Identity 持有自己数据库的 migration authority。
`unrelated_probe` 是无业务数据的拒绝访问样本；其 CONNECT 权限已从 PUBLIC 撤销。
失败注入测试在修改测试 trigger 前核对数据库的隔离标识。

## 定向验证

骨架 HTTP、Flyway、SQL 映射和账号边界：

```sh
./mvnw -q test -pl services/identity -am -Dtest=IdentityBoundaryHttpTests,IdentityMapperIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

用户完成事务方法后运行真正的账户行为验收：

```sh
./mvnw -q test -pl services/identity -am -Dtest=LocalAccountPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

后者包含正常提交、并发重复邮箱，以及凭据 / 默认授权写入失败时的整笔回滚。
在 `HUMAN_CORE_READY` 阶段，它必须因未实现方法而失败；不得改成期待异常、跳过或补写完整实现。
未设置 `IDENTITY_DATABASE_TESTS=true` 时，数据库测试会被 JUnit 跳过；不能将这种结果称为数据库验收通过。

数据库测试只在此隔离库创建合成样本和测试 trigger，不 TRUNCATE、不清理旧业务数据。
Mapper 样本随测试事务回滚；未来账户行为测试提交的合成账户会留在隔离库，方便排查。

## 手动启动

```sh
./mvnw -q package -pl services/identity -am -DskipTests
java -jar services/identity/target/identity-service-0.0.1-SNAPSHOT.jar
```

默认 HTTP 地址为 `http://127.0.0.1:8081`，只允许 `GET /actuator/health`。
没有注册 / 登录 / 退出 / Session API；不能用旧应用的 `REGISTRATION_ENABLED=false` 开启自动身份。
`-DskipTests` 只是打包，不是验证证据。Cloud 在本 slice 仅导入 BOM，没有 Cloud starter/runtime 验证。

暂停环境时可使用同一 compose 文件执行 `stop`，保留新卷；本合同不提供自动删卷或清库步骤。

## 跨应用重启的持久化验收

先加载上述环境并执行当前版本的 18 项集中测试和打包：

```sh
./mvnw -q package -o -pl services/identity -am -Dtest=IdentityBoundaryHttpTests,IdentityMapperIntegrationTests,LocalAccountPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

这些测试通过 production bean 提交合成账户。使用 `identity_app` 执行下面的只读 SQL，
在应用启动前、第一次 JVM 启动健康后、停止并启动第二个 JVM 健康后各保存一份结果，再比较三份结果。
比较期间不运行其他账户写入测试。启动使用同一 JAR、同一隔离数据库，每次以 `/actuator/health` 的 200 / UP 为就绪条件。
此检查不要求新增账户 HTTP 查询接口，也不重启数据库容器或删除数据。

```sql
SELECT 'app_user' AS relation_name, id::text AS record_key, md5(to_jsonb(account)::text) AS row_digest
FROM app_user account
UNION ALL
SELECT 'auth_identity', id::text, md5(to_jsonb(identity_row)::text)
FROM auth_identity identity_row
UNION ALL
SELECT 'local_password_credential', auth_identity_id::text, md5(to_jsonb(credential)::text)
FROM local_password_credential credential
UNION ALL
SELECT 'app_access_grant', user_id::text || ':' || app_code, md5(to_jsonb(access_grant)::text)
FROM app_access_grant access_grant
ORDER BY relation_name, record_key;
```

摘要只用于合成数据的一致性比较，不输出邮箱或 verifier 原文，不作为密码算法或防篡改机制。
本轮具体结果见 `docs/features/IDENTITY.md`；运行日志和摘要位于忽略的 `services/identity/target/`，实际密码不提交。

## R1-F Redis Session 基础

Compose 新增独立 Redis `7.2.16-alpine3.21`，仅绑定 `127.0.0.1:55442`，不连接旧应用的 Redis。
Redis 7.2 分支的维护状态见 [Redis Open Source version management](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/)，
固定镜像 tag 来自 [官方镜像清单](https://github.com/docker-library/official-images/blob/master/library/redis)；不升级旧 server 或根 Compose。
本地验证禁用 RDB / AOF，数据放在 tmpfs，Redis 停止或重启后会话丢失，账户数据仍在 PostgreSQL。
这是本机学习环境，不是生产 Redis 的访问控制、传输保护或持久化方案。

Identity 必须显式配置 `IDENTITY_REDIS_HOST`；端口默认 55442，连接与操作超时均为 1s。
使用独立 namespace `daily-language:identity:session:v1`、default 非索引 repository、24h idle TTL。
JSON 只额外允许 AuthenticatedAccount，预期属性来自 R1-E 的空 credentials 认证结果，不保存原始密码或 verifier；serializer 不负责清理任意属性中的敏感字符串，不使用 Java native serialization。
Redis 不可用时保存失败，不回退到 JVM 内存 Session；完整服务的健康检查也会反映 Redis 故障。

Cookie 默认 SESSION / HttpOnly / Secure / SameSite=Lax / Path=/，不设 Domain / Max-Age。
当前 HTTP 仍不创建登录会话；未来本机 HTTP 登录验收需要显式设置 `IDENTITY_SESSION_COOKIE_SECURE=false`，
不得因此关闭 Hosted 默认 Secure。会话轮换、退出、登录接口与跨服务核验尚未实现。

```sh
# 单独执行 serializer 与现有 HTTP 边界，不需要真实 Redis。
./mvnw -q -o -pl services/identity -am test -Dtest=SessionSerializationTests,IdentityBoundaryHttpTests -Dsurefire.failIfNoSpecifiedTests=false
# 确认以上 Redis 地址指向独立验证实例后，显式开启真实存取与不可用测试。
IDENTITY_REDIS_TESTS=true IDENTITY_REDIS_UNAVAILABLE_TESTS=true ./mvnw -q -o -pl services/identity -am test -Dtest=RedisSessionIntegrationTests,RedisSessionUnavailableIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

真实存取测试只创建随机 Session 并在 finally 删除，不执行 FLUSHDB / FLUSHALL；默认未开启时两项 integration 会跳过，
跳过不能算验收通过。HTTP 测试仅关闭其最小上下文的 Redis health contributor，真实 Redis 存取由显式 integration 验证。
