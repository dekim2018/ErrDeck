-- 부하 실험이 쓰는 프로젝트(scripts/experiments, scripts/chaos). 대기열과 저장 경로를 보려고 한도를 사실상 없앤다.
-- 프로젝트 ID 3 과 키 loadgenhigh3 은 17 작업부터 스크립트에 박혀 있는 값이다. 데모 프로젝트(9001)는 SDK 부담 실험에서
-- 추적을 모두 보내도 한도(분당 6,000)에 걸려 SDK 가 물러나지 않게 같은 값으로 올린다.
-- 여러 번 실행해도 같은 상태다.
--
--   docker compose exec -T postgres psql -U errdeck < scripts/demo/seed.sql
--   docker compose exec -T postgres psql -U errdeck < scripts/experiments/seed.sql

INSERT INTO systems (name) VALUES ('부하 실험') ON CONFLICT (name) DO NOTHING;

INSERT INTO projects (id, system_id, slug, platform, allowed_origins, rate_limit_per_min)
OVERRIDING SYSTEM VALUE
SELECT 3, id, 'loadgen-high', 'spring', '{}', 10000000 FROM systems WHERE name = '부하 실험'
ON CONFLICT DO NOTHING;

INSERT INTO project_keys (project_id, public_key) VALUES (3, 'loadgenhigh3') ON CONFLICT (public_key) DO NOTHING;

UPDATE projects SET rate_limit_per_min = 10000000 WHERE id IN (3, 9001);

-- 직접 ID 를 넣었으므로 다음에 화면에서 만드는 프로젝트가 3 과 부딪치지 않게 순번을 맞춘다.
SELECT setval(pg_get_serial_sequence('projects', 'id'), GREATEST((SELECT max(id) FROM projects WHERE id < 9000), 3));
