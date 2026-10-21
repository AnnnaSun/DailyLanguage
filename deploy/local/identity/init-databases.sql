-- 仅供新建的 R1 隔离测试实例；不是旧业务实例的初始化或迁移脚本。
\getenv identity_password IDENTITY_TEST_PASSWORD
CREATE ROLE identity_app LOGIN PASSWORD :'identity_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE identity OWNER TO identity_app;
REVOKE ALL ON DATABASE identity FROM PUBLIC;
COMMENT ON DATABASE identity IS 'DAILYLANGUAGE_R1_ISOLATED_VERIFICATION_ONLY';

-- 用无业务数据的数据库验证账号边界，不读取真实 Language / PersonalNote 数据库。
CREATE DATABASE unrelated_probe;
REVOKE ALL ON DATABASE unrelated_probe FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
