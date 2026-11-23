-- ingest가 DSN 키를 확인하는 데 필요한 두 테이블(10.1). 나머지 테이블은 06 작업에서 더한다.
-- system_id 의 외래 키는 systems 테이블을 만드는 06 작업에서 건다.

CREATE TABLE projects (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    system_id          BIGINT      NOT NULL,
    slug               TEXT        NOT NULL UNIQUE,
    platform           TEXT        NOT NULL CHECK (platform IN ('spring', 'vue')),
    -- 분당 받을 봉투 수(5.5)
    rate_limit_per_min INTEGER     NOT NULL DEFAULT 6000 CHECK (rate_limit_per_min > 0),
    allowed_origins    TEXT[]      NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 프로젝트 ID는 ClickHouse 의 UInt32 열에 들어간다(10.2).
    CHECK (id <= 4294967295)
);

CREATE TABLE project_keys (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT      NOT NULL REFERENCES projects (id),
    public_key TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 교체하는 동안 옛 키를 조금 더 받도록 폐기 시각을 미래로 잡을 수 있다(15.5).
    revoked_at TIMESTAMPTZ
);

CREATE INDEX project_keys_project_id ON project_keys (project_id);
