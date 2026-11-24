-- 3.2 의 시스템 4개와 프로젝트 13개(23 작업). 여러 번 실행해도 같다.
-- DSN 키는 처음 만들 때만 무작위로 만든다. 키는 콘솔의 프로젝트 설정이나 아래 마지막 SELECT 로 본다.
--   docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql
-- 콘솔에서 보려면 가입한 사용자를 각 시스템의 OWNER 로 넣는다(파일 끝의 주석).

INSERT INTO systems (name) VALUES ('팬파라이브'), ('CoPage'), ('ErrDeck'), ('데모 앱') ON CONFLICT (name) DO NOTHING;

WITH wanted (system_name, slug, platform, origins) AS (VALUES
    ('팬파라이브', 'gateway', 'spring', ARRAY[]::text[]),
    ('팬파라이브', 'auth-service', 'spring', ARRAY[]::text[]),
    ('팬파라이브', 'broadcast-service', 'spring', ARRAY[]::text[]),
    ('팬파라이브', 'interaction-service', 'spring', ARRAY[]::text[]),
    ('팬파라이브', 'fandom-service', 'spring', ARRAY[]::text[]),
    ('팬파라이브', 'fanpalive-web', 'vue', ARRAY['https://fanpalive.duckdns.org']),
    ('CoPage', 'copage-app', 'spring', ARRAY[]::text[]),
    ('CoPage', 'copage-web', 'vue', ARRAY['https://copage.duckdns.org']),
    ('ErrDeck', 'api', 'spring', ARRAY[]::text[]),
    ('ErrDeck', 'worker', 'spring', ARRAY[]::text[]),
    ('ErrDeck', 'errdeck-web', 'vue', ARRAY['https://errdeck.duckdns.org']),
    ('데모 앱', 'demo-api', 'spring', ARRAY[]::text[]),
    ('데모 앱', 'demo-web', 'vue', ARRAY['http://localhost:8092'])
)
INSERT INTO projects (system_id, slug, platform, allowed_origins)
SELECT s.id, w.slug, w.platform, w.origins FROM wanted w JOIN systems s ON s.name = w.system_name
WHERE NOT EXISTS (SELECT 1 FROM projects p WHERE p.slug = w.slug);

INSERT INTO project_keys (project_id, public_key)
SELECT p.id, replace(gen_random_uuid()::text, '-', '') FROM projects p
WHERE NOT EXISTS (SELECT 1 FROM project_keys k WHERE k.project_id = p.id AND k.revoked_at IS NULL);

-- ErrDeck 자신의 자원 경보(13.6)는 api 프로젝트의 'resource' 규칙으로 받는다. 웹훅 주소를 넣고 주석을 푼다.
-- INSERT INTO alert_rules (project_id, kind, channel, target)
-- SELECT p.id, 'resource', 'discord', 'https://discord.com/api/webhooks/...' FROM projects p JOIN systems s ON s.id = p.system_id
-- WHERE s.name = 'ErrDeck' AND p.slug = 'api';

-- 가입한 사용자를 네 시스템의 OWNER 로:
-- INSERT INTO system_members (system_id, user_id, role)
-- SELECT s.id, u.id, 'OWNER' FROM systems s, users u WHERE u.email = '나의 이메일' ON CONFLICT DO NOTHING;

SELECT s.name AS system, p.slug, p.platform, k.public_key, p.id AS project_id
FROM projects p JOIN systems s ON s.id = p.system_id JOIN project_keys k ON k.project_id = p.id AND k.revoked_at IS NULL
WHERE s.name IN ('팬파라이브', 'CoPage', 'ErrDeck', '데모 앱') ORDER BY s.name, p.slug;
