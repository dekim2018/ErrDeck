-- 데모 앱 시스템과 프로젝트 2개(3.2). 화면에서 프로젝트를 만들기 전(11 작업 전)에도 데모 앱의 DSN이 바뀌지 않도록
-- ID와 키를 고정한다. 프로젝트 ID 9001, 9002 는 일반 프로젝트의 ID가 닿기 어려운 값으로 골랐다.
-- 여러 번 실행해도 같은 상태다.
--
--   docker compose exec -T postgres psql -U errdeck < scripts/demo/seed.sql

INSERT INTO systems (name) VALUES ('데모 앱') ON CONFLICT (name) DO NOTHING;

INSERT INTO projects (id, system_id, slug, platform, allowed_origins)
OVERRIDING SYSTEM VALUE
SELECT 9001, id, 'demo-api', 'spring', '{}' FROM systems WHERE name = '데모 앱'
ON CONFLICT DO NOTHING;

INSERT INTO projects (id, system_id, slug, platform, allowed_origins)
OVERRIDING SYSTEM VALUE
SELECT 9002, id, 'demo-web', 'vue', '{http://localhost:5173,http://localhost:8092}' FROM systems WHERE name = '데모 앱'
ON CONFLICT DO NOTHING;

INSERT INTO project_keys (project_id, public_key) VALUES (9001, 'demoapikey') ON CONFLICT (public_key) DO NOTHING;
INSERT INTO project_keys (project_id, public_key) VALUES (9002, 'demowebkey') ON CONFLICT (public_key) DO NOTHING;
