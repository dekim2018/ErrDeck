-- 팬파라이브를 같은 맥에서 띄워 ErrDeck 에 붙일 때(로컬 연결, docs/INTEGRATION.md). systems.sql 다음에 실행한다.
--   docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql
--   docker compose exec -T postgres psql -U errdeck < scripts/seed/fanpalive-local.sql

-- 팬파라이브 화면의 개발 서버(Vite 5173. ErrDeck 콘솔과 겹치면 다른 포트로)에서 오는 봉투를 받는다(5.2).
UPDATE projects SET allowed_origins = ARRAY['http://localhost:5173', 'http://localhost:5174', 'http://localhost:15173']
WHERE slug = 'fanpalive-web';

-- 업타임 모니터: gateway 의 상태 확인. 팬파라이브는 평소 꺼 두므로 만들자마자 중지해 둔다(9.1). 콘솔에서 다시 켠다.
INSERT INTO monitors (project_id, name, url, interval_seconds, paused_until)
SELECT p.id, '팬파라이브 gateway', 'http://host.docker.internal:8080/actuator/health', 60, '9999-12-31' FROM projects p
WHERE p.slug = 'gateway' AND NOT EXISTS (SELECT 1 FROM monitors m WHERE m.project_id = p.id);

-- 팬파라이브에 넣을 DSN. ingest 는 호스트의 18080 이다. 서비스마다 자기 프로젝트의 DSN 을 쓴다.
SELECT p.slug, 'http://' || k.public_key || '@localhost:18080/' || p.id AS dsn
FROM projects p JOIN systems s ON s.id = p.system_id JOIN project_keys k ON k.project_id = p.id AND k.revoked_at IS NULL
WHERE s.name = '팬파라이브' ORDER BY p.slug;
