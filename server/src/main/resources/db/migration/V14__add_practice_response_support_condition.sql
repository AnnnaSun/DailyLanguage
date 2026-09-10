-- M1-S8T-B：practice_response 增加四类教学支架的 support-condition snapshot。
-- ADD COLUMN ... NOT NULL DEFAULT 先把既有（migration 前）response 回填为 UNKNOWN——
-- missing historical data = UNKNOWN，不能解释成 NOT_PROVIDED；随后移除 default，
-- 要求所有新 insert 显式写入完整 snapshot。

ALTER TABLE practice_response
    ADD COLUMN demonstration_exposure VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN explanation_exposure VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN hint_exposure VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN response_frame_exposure VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE practice_response
    ADD CONSTRAINT ck_practice_response_demonstration_exposure
        CHECK (demonstration_exposure IN ('UNKNOWN', 'NOT_PROVIDED', 'PROVIDED', 'OPENED')),
    ADD CONSTRAINT ck_practice_response_explanation_exposure
        CHECK (explanation_exposure IN ('UNKNOWN', 'NOT_PROVIDED', 'PROVIDED', 'OPENED')),
    ADD CONSTRAINT ck_practice_response_hint_exposure
        CHECK (hint_exposure IN ('UNKNOWN', 'NOT_PROVIDED', 'PROVIDED', 'OPENED')),
    ADD CONSTRAINT ck_practice_response_response_frame_exposure
        CHECK (response_frame_exposure IN ('UNKNOWN', 'NOT_PROVIDED', 'PROVIDED', 'OPENED'));

ALTER TABLE practice_response
    ALTER COLUMN demonstration_exposure DROP DEFAULT,
    ALTER COLUMN explanation_exposure DROP DEFAULT,
    ALTER COLUMN hint_exposure DROP DEFAULT,
    ALTER COLUMN response_frame_exposure DROP DEFAULT;

COMMENT ON TABLE practice_response IS
    'Learner 对 material step 的已接受 response：identity 是 (session_id, step_id)，同一 step 只保存首次接受的 exact text 与首次的四类 support-condition snapshot（demonstration/explanation/hint/responseFrame 的可观察暴露条件）；不保存 correctness、semantic result、Model 输出或 Evidence。';
COMMENT ON COLUMN practice_response.demonstration_exposure IS
    'demonstration 支架在回答前的可观察暴露条件：UNKNOWN=系统无法确认（含 migration 前历史），NOT_PROVIDED=确认未提供，PROVIDED=系统直接提供，OPENED=learner 主动打开；只是交互事实，不是理解或掌握。';
COMMENT ON COLUMN practice_response.explanation_exposure IS
    'explanation 支架在回答前的可观察暴露条件，取值语义同 demonstration_exposure。';
COMMENT ON COLUMN practice_response.hint_exposure IS
    'hint 支架在回答前的可观察暴露条件，取值语义同 demonstration_exposure。';
COMMENT ON COLUMN practice_response.response_frame_exposure IS
    'responseFrame 支架在回答前的可观察暴露条件，取值语义同 demonstration_exposure。';
