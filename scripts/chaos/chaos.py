#!/usr/bin/env python3
"""18.4 의 장애 실험 6종. 자원 실험의 도구(scripts/experiments/resources.py)를 같이 쓴다.

    python3 scripts/chaos/chaos.py <실험>

    worker-kill      처리 중에 worker 를 docker kill. 유실 0건, 중복 건수
    clickhouse-stop  ClickHouse 를 2분 정지. 메모리가 계속 늘지 않고, ingest 가 503, 재개 후 복구
    redis-restart    부하 중 Redis 재시작. 잃은 건수가 마지막 1초 분량 이내
    postgres-stop    PostgreSQL 을 1분 정지. ingest 는 키 캐시로 받고, worker 는 멈췄다 재개
    all-down         데모 앱에 부하를 건 채 이 서비스 전체를 내림. 데모 앱 요청 실패 0건, SDK 메모리
    two-workers      worker 2대로 부하와 업타임 점검. 중복 점검 0건

실험은 하나씩 돌린다. 공통: 한도가 큰 프로젝트(3)에 초당 500건(봉투 50개, 10건씩)을 보내고, 보낸 것 중 202 를 받은 건수와
ClickHouse 의 event_ticks 합계를 비교한다. ingest 가 503 을 줬지만 실제로 적재된 것(18.2 의 열린 문제)이 있어 "더 받음"이 생길 수 있다.
"""
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "experiments"))
import resources as r  # noqa: E402

RATE = 500
# 데모 앱은 같은 네트워크의 ingest 로 보낸다. 기본값(호스트 8080)은 다른 프로젝트가 쓸 수 있다.
DEMO = {"demo-api": {"env": {"DEMO_ERRDECK_DSN": "http://demoapikey@ingest:8080/9001", "DEMO_SCHEDULED_FAILURE_ENABLED": "false"}}}
BATCH = 10


def counted(start_iso):
    """시작한 분부터의 이벤트 수. event_ticks 는 저장하지 않는 Null 테이블이라 분 단위 집계(issue_stats_1m)를 센다(10.3)."""
    total = r.clickhouse(f"SELECT sum(times) FROM issue_stats_1m WHERE project_id = {r.PROJECT} "
                         f"AND minute >= toStartOfMinute(parseDateTime64BestEffort('{start_iso}'))")
    return int(total or 0)


def stored_since(start_iso, baseline):
    """시작 뒤의 이벤트 수(부하 전에 같은 분에 있던 것을 뺌)와 event_id 중복 수."""
    dup = r.clickhouse(f"SELECT count() - uniqExact(event_id) FROM events WHERE project_id = {r.PROJECT} "
                       f"AND timestamp >= parseDateTime64BestEffort('{start_iso}')")
    return counted(start_iso) - baseline, int(dup or 0)


def load(seconds, result):
    out, _ = r.loadgen("sustained", f"--dsn=http://{r.KEY}@ingest:8080/{r.PROJECT}", f"--rate={RATE}", f"--duration={seconds}s",
                       f"--batch={BATCH}", f"--fingerprints={r.FINGERPRINTS}")
    result.update(out)


def run_with_load(seconds, fault, settle=60):
    """부하를 걸고 fault() 를 부른 뒤, 대기열이 빌 때까지 기다려 보낸 것과 저장된 것을 비교한다."""
    r.up({})
    r.reset_queue()
    r.compose("up", "-d", "--wait", "worker")
    start = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(time.time() - 2))
    baseline = counted(start)
    result = {}
    t = threading.Thread(target=load, args=(seconds, result))
    t.start()
    notes = fault() or {}
    t.join()
    deadline = time.time() + settle * 4
    while time.time() < deadline and (r.xlen() > 0):
        time.sleep(2)
    time.sleep(settle / 4)
    accepted = result.get("codes", {}).get("202", 0) * BATCH
    stored, duplicates = stored_since(start, baseline)
    return {"loadgen": result, "accepted_events": accepted, "stored_events": stored, "lost": max(0, accepted - stored),
            "extra": max(0, stored - accepted), "duplicate_event_ids": duplicates, "queue_left": r.xlen(), **notes}


def mem(service):
    try:
        out = r.sh(["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", f"errdeck-{service}-1"]).strip()
        return out.split("/")[0].strip()
    except Exception:
        return None


def worker_kill():
    def fault():
        time.sleep(20)
        r.log("worker 를 docker kill")
        r.sh(["docker", "kill", "errdeck-worker-1"])
        time.sleep(5)
        r.compose("up", "-d", "--wait", "worker")
        r.log("worker 다시 띄움")
    return run_with_load(60, fault)


def clickhouse_stop():
    samples = []

    def fault():
        time.sleep(10)
        r.log("ClickHouse 정지 2분")
        r.compose("stop", "clickhouse")
        for _ in range(12):
            samples.append({s: mem(s) for s in ("ingest", "worker", "redis")} | {"queue": r.xlen()})
            time.sleep(10)
        r.compose("start", "clickhouse")
        r.compose("up", "-d", "--wait", "clickhouse")
        r.log("ClickHouse 재개")
        return {"during_stop": samples[::3]}
    return run_with_load(170, fault, settle=90)


def redis_restart():
    def fault():
        time.sleep(20)
        r.log("Redis 재시작")
        r.compose("restart", "redis")
        r.log("Redis 다시 뜸")
    return run_with_load(60, fault)


def postgres_stop():
    def fault():
        time.sleep(15)
        r.log("PostgreSQL 정지 1분")
        r.compose("stop", "postgres")
        time.sleep(60)
        r.compose("start", "postgres")
        r.compose("up", "-d", "--wait", "postgres")
        r.log("PostgreSQL 재개")
    return run_with_load(100, fault, settle=60)


def all_down():
    """데모 앱에 요청 부하를 건 채로 ingest, worker, api 를 내린다. 데모 앱은 영향을 받으면 안 된다(2.2 의 격리)."""
    r.up(DEMO)
    r.compose("up", "-d", "--wait", "demo-api")
    result = {}

    def demo_load():
        out, _ = r.loadgen("http", "--url=http://demo-api:8090/demo/handled/1", "--rate=100", "--duration=90s")
        result.update(out)

    t = threading.Thread(target=demo_load)
    t.start()
    time.sleep(20)
    before = mem("demo-api")
    r.log("이 서비스 전체 정지")
    r.compose("stop", "ingest", "worker", "api", "redis", "clickhouse", "postgres")
    time.sleep(50)
    after = mem("demo-api")
    t.join()
    r.compose("up", "-d", "--wait", "postgres", "redis", "clickhouse", "api", "ingest", "worker")
    return {"demo_loadgen": result, "demo_mem_before": before, "demo_mem_after_50s_down": after}


def two_workers():
    """worker 2대로 부하를 처리하고, 30초 주기 모니터의 분당 점검 수를 본다."""
    out = run_with_load_scaled()
    return out


def run_with_load_scaled():
    r.up(DEMO)
    r.compose("up", "-d", "--wait", "--scale", "worker=2", "worker", "demo-api")
    monitor = r.sh(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "errdeck", "-tAc",
                    f"INSERT INTO monitors (project_id, name, url, interval_seconds) VALUES ({r.PROJECT}, 'chaos', "
                    "'http://demo-api:8090/actuator/health', 30) RETURNING id"]).strip().splitlines()[0]
    r.reset_queue()
    r.compose("up", "-d", "--wait", "--scale", "worker=2", "worker")
    start = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(time.time() - 2))
    baseline = counted(start)
    result = {}
    load(150, result)
    while r.xlen() > 0:
        time.sleep(2)
    time.sleep(15)
    accepted = result.get("codes", {}).get("202", 0) * BATCH
    stored, dup = stored_since(start, baseline)
    checks = r.clickhouse(f"SELECT toStartOfMinute(timestamp) m, count() FROM uptime_checks WHERE monitor_id = {monitor} "
                          "GROUP BY m ORDER BY m FORMAT TSV")
    r.sh(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "errdeck", "-c", f"DELETE FROM monitors WHERE id = {monitor}"])
    r.compose("up", "-d", "--scale", "worker=1", "worker")
    return {"loadgen": result, "accepted_events": accepted, "stored_events": stored, "duplicate_event_ids": dup,
            "checks_per_minute": checks.splitlines()}


EXPERIMENTS = {
    "worker-kill": worker_kill,
    "clickhouse-stop": clickhouse_stop,
    "redis-restart": redis_restart,
    "postgres-stop": postgres_stop,
    "all-down": all_down,
    "two-workers": two_workers,
}


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(names) != 1 or names[0] not in EXPERIMENTS:
        print(__doc__)
        sys.exit(2)
    name = names[0]
    commit = r.sh(["git", "rev-parse", "--short", "HEAD"]).strip()
    r.log(f"{name} 시작, 커밋 {commit}")
    result = {"experiment": name, "commit": commit, "started": time.strftime("%Y-%m-%d %H:%M:%S"), "result": EXPERIMENTS[name]()}
    path = r.RUN / f"chaos-{name}-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
