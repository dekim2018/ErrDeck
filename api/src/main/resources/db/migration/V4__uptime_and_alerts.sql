-- 20, 21 작업: 업타임 판정 상태와 알림 판정에 필요한 열.

-- 재발 알림(16.1)이 언제 재발했는지 알 수 있게 한다. 재발로 바뀌는 순간에만 시각을 남긴다.
ALTER TABLE issues ADD COLUMN regressed_at TIMESTAMPTZ;
CREATE INDEX issues_regressed_at ON issues (regressed_at) WHERE regressed_at IS NOT NULL;
CREATE INDEX issues_created_at ON issues (created_at);

CREATE FUNCTION issues_mark_regressed() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'regressed' AND OLD.status IS DISTINCT FROM 'regressed' THEN
        NEW.regressed_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER issues_regressed BEFORE UPDATE OF status ON issues
    FOR EACH ROW EXECUTE FUNCTION issues_mark_regressed();

-- 9.3 의 판정은 연속 실패와 연속 성공 횟수로 한다.
ALTER TABLE monitors ADD COLUMN name TEXT;
ALTER TABLE monitors ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE monitors ADD COLUMN consecutive_successes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE monitors ADD COLUMN last_checked_at TIMESTAMPTZ;
ALTER TABLE monitors ADD COLUMN last_error TEXT;
ALTER TABLE monitors ADD COLUMN cert_expires_at TIMESTAMPTZ;

-- 발송 내용과 마지막 실패 이유. 발송 담당이 이 행을 보고 보낸다.
ALTER TABLE notifications ADD COLUMN kind TEXT;
ALTER TABLE notifications ADD COLUMN title TEXT;
ALTER TABLE notifications ADD COLUMN body TEXT;
ALTER TABLE notifications ADD COLUMN link TEXT;
ALTER TABLE notifications ADD COLUMN last_error TEXT;
CREATE INDEX notifications_rule_created ON notifications (rule_id, created_at DESC);

-- Alertmanager 가 넘긴 자원 경보(13.6)를 받을 규칙. 이 서비스 자신을 감시하는 프로젝트에 단다.
ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_kind_check;
ALTER TABLE alert_rules ADD CONSTRAINT alert_rules_kind_check
    CHECK (kind IN ('new_issue', 'regression', 'frequency', 'latency', 'monitor_down', 'cert_expiry', 'resource'));
