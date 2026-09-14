-- S9C1：为 learning_task 增加 planner enrichment 的 durable user-facing 投影。
-- recommendation_reason 只允许出现在 MODEL_ENRICHED 行；既有 deterministic 行保持 NULL，
-- 不回填、不改写任何既有数据。

ALTER TABLE learning_task
    ADD COLUMN recommendation_reason VARCHAR(240);

COMMENT ON COLUMN learning_task.recommendation_reason IS
    'MODEL_ENRICHED planning 写入的 bounded recommendation reason（已 NFC、无边界空白、无换行/control 字符、1–240 Unicode code points）；DETERMINISTIC_BUILT_IN_FALLBACK 行必须为 NULL。';

-- 只扩展 closed planning_reason 枚举，不修改 V8 文件本体。V14 前的既有行全部是
-- DETERMINISTIC_BUILT_IN_FALLBACK，重新添加的约束在 empty-schema 与 V14→V15 upgrade
-- 两条路径下都会通过既有行验证。
ALTER TABLE learning_task
    DROP CONSTRAINT ck_learning_task_planning_reason;
ALTER TABLE learning_task
    ADD CONSTRAINT ck_learning_task_planning_reason
        CHECK (planning_reason IN ('DETERMINISTIC_BUILT_IN_FALLBACK', 'MODEL_ENRICHED'));

-- planningReason / reason pairing 在数据库层同样 fail closed：Java domain 拒绝的组合
-- 不能通过直接写库绕过。
ALTER TABLE learning_task
    ADD CONSTRAINT ck_learning_task_planning_reason_recommendation_pairing
        CHECK (
            (planning_reason = 'DETERMINISTIC_BUILT_IN_FALLBACK' AND recommendation_reason IS NULL)
            OR (planning_reason = 'MODEL_ENRICHED' AND recommendation_reason IS NOT NULL)
        );

-- present reason 的文本边界与 Java domain 对齐：UTF-8 下 VARCHAR(240) 即最多 240 个
-- Unicode code points；再拒绝空串、边界空白、control / line separator 字符与非 NFC 文本。
ALTER TABLE learning_task
    ADD CONSTRAINT ck_learning_task_recommendation_reason
        CHECK (
            recommendation_reason IS NULL
            OR (
                recommendation_reason <> ''
                AND recommendation_reason !~ '^[[:space:]]'
                AND recommendation_reason !~ '[[:space:]]$'
                AND recommendation_reason !~ '[[:cntrl:]\u2028\u2029]'
                AND recommendation_reason IS NFC NORMALIZED
            )
        );
