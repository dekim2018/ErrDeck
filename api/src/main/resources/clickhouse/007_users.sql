-- worker 와 api 는 서로 다른 사용자를 쓴다(10.5). 콘솔의 무거운 조회가 수집을 방해하지 못하게
-- api 사용자에게 쿼리별 실행 시간, 메모리, 스레드 상한을 건다. 값은 ClickHouse 메모리 상한 2,048MB(14.7) 안에 맞춘다.
-- 비밀번호는 저장소에 두지 않고 실행기가 설정에서 채운다.
CREATE SETTINGS PROFILE IF NOT EXISTS errdeck_api_profile SETTINGS
  max_execution_time = 10,
  max_memory_usage = 536870912,
  max_threads = 2,
  readonly = 2;

CREATE SETTINGS PROFILE IF NOT EXISTS errdeck_worker_profile SETTINGS
  max_execution_time = 10,
  max_memory_usage = 536870912;

CREATE USER IF NOT EXISTS ${worker_user} IDENTIFIED WITH sha256_password BY '${worker_password}'
  SETTINGS PROFILE errdeck_worker_profile;

CREATE USER IF NOT EXISTS ${api_user} IDENTIFIED WITH sha256_password BY '${api_password}'
  SETTINGS PROFILE errdeck_api_profile;

GRANT SELECT, INSERT ON ${database}.* TO ${worker_user};

GRANT SELECT ON ${database}.* TO ${api_user};
