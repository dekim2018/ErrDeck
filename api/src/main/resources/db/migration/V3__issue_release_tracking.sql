-- 15 작업: 릴리스 화면의 "새 이슈"와 무시의 횟수 조건(6.6).
-- first_release: 이슈를 만든 첫 이벤트의 릴리스. 릴리스별 새 이슈 수를 센다.
-- ignored_count: 무시한 뒤에 들어온 이벤트 수. ignored_until_count 에 닿으면 미해결로 돌아간다.
ALTER TABLE issues ADD COLUMN first_release TEXT;
ALTER TABLE issues ADD COLUMN ignored_count BIGINT NOT NULL DEFAULT 0;
UPDATE issues SET first_release = last_release WHERE first_release IS NULL;
CREATE INDEX issues_first_release ON issues (project_id, first_release);
CREATE INDEX releases_project_created ON releases (project_id, created_at DESC);
