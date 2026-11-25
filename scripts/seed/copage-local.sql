-- CoPage 를 같은 맥에서 띄워 ErrDeck 에 붙일 때(로컬 연결, docs/INTEGRATION.md). systems.sql 다음에 실행한다.
--   docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql
--   docker compose exec -T postgres psql -U errdeck < scripts/seed/copage-local.sql

-- CoPage 화면의 개발 서버(Vite 5173, ErrDeck 콘솔과 겹치면 5174)에서 오는 봉투를 받는다(5.2).
UPDATE projects SET allowed_origins = ARRAY['http://localhost:5173', 'http://localhost:5174']
WHERE slug = 'copage-web';

-- 업타임 모니터: CoPage 앱의 상태 확인(관리 포트). 이미 있으면 만들지 않는다.
INSERT INTO monitors (project_id, name, url, interval_seconds)
SELECT p.id, 'CoPage 앱 상태', 'http://host.docker.internal:8081/actuator/health', 30 FROM projects p
WHERE p.slug = 'copage-app' AND NOT EXISTS (SELECT 1 FROM monitors m WHERE m.project_id = p.id);

-- CoPage 에 넣을 DSN. ingest 는 호스트의 18080 이다.
SELECT p.slug, 'http://' || k.public_key || '@localhost:18080/' || p.id AS dsn
FROM projects p JOIN project_keys k ON k.project_id = p.id AND k.revoked_at IS NULL
WHERE p.slug IN ('copage-app', 'copage-web') ORDER BY p.slug;
