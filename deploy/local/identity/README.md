# R1-A 本地隔离验证环境

此目录仅准备 Identity 的本地学习环境，不是生产部署，也不操作旧 Language / PersonalNote 实例。
独立 PostgreSQL 18 容器使用端口 `127.0.0.1:55441` 和本项目专用新卷，不加载 pgvector extension。
隔离实例是验证安排，不改变 C24 目标平台共用实例、每服务独立库与账号的决定。

## 凭据与启动

首次使用时，将 `.env.example` 复制到仓库外的私有文件，填写两个不同的随机密码，文件权限设为 `600`。
当前本机准备的文件为 `/private/tmp/dailylanguage-r1-identity.env`，没有提交实际密码。
后续继续使用同一文件：PostgreSQL 初始化 SQL 只对空的新卷执行，修改文件不会自动修改已有账号密码。
文件丢失时先恢复凭据或明确处理账号，不默认删除卷、清库或重新初始化。

在仓库根目录执行：

```sh
docker compose --env-file /private/tmp/dailylanguage-r1-identity.env -f deploy/local/identity/compose.yaml up -d --wait
set -a
source /private/tmp/dailylanguage-r1-identity.env
set +a
```

`identity_test_admin` 仅用于隔离实例初始化；Identity 使用 `identity_app`，该角色不是超级用户，
没有 CREATEDB / CREATEROLE。Identity 持有自己数据库的 migration authority。
`unrelated_probe` 是无业务数据的拒绝访问样本；其 CONNECT 权限已从 PUBLIC 撤销。
失败注入测试在修改测试 trigger 前核对数据库的隔离标识。

## 定向验证

骨架 HTTP、Flyway、SQL 映射和账号边界：

```sh
mvn -q test -pl services/identity -am -Dtest=IdentityBoundaryHttpTests,IdentityMapperIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

用户完成事务方法后运行真正的账户行为验收：

```sh
mvn -q test -pl services/identity -am -Dtest=LocalAccountPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
```

后者包含正常提交、并发重复邮箱，以及凭据 / 默认授权写入失败时的整笔回滚。
在 `HUMAN_CORE_READY` 阶段，它必须因未实现方法而失败；不得改成期待异常、跳过或补写完整实现。
未设置 `IDENTITY_DATABASE_TESTS=true` 时，数据库测试会被 JUnit 跳过；不能将这种结果称为数据库验收通过。

数据库测试只在此隔离库创建合成样本和测试 trigger，不 TRUNCATE、不清理旧业务数据。
Mapper 样本随测试事务回滚；未来账户行为测试提交的合成账户会留在隔离库，方便排查。

## 手动启动

```sh
mvn -q package -pl services/identity -am -DskipTests
java -jar services/identity/target/identity-service-0.0.1-SNAPSHOT.jar
```

默认 HTTP 地址为 `http://127.0.0.1:8081`，只允许 `GET /actuator/health`。
没有注册 / 登录 / 退出 / Session API；不能用旧应用的 `REGISTRATION_ENABLED=false` 开启自动身份。
`-DskipTests` 只是打包，不是验证证据。Cloud 在本 slice 仅导入 BOM，没有 Cloud starter/runtime 验证。

暂停环境时可使用同一 compose 文件执行 `stop`，保留新卷；本合同不提供自动删卷或清库步骤。

## 跨应用重启的持久化验收

先加载上述环境并执行当前版本的 18 项集中测试和打包：

```sh
mvn -q package -o -pl services/identity -am -Dtest=IdentityBoundaryHttpTests,IdentityMapperIntegrationTests,LocalAccountPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
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
