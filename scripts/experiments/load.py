#!/usr/bin/env python3
"""18.2 의 부하 실험 중 남은 4종. 자원 실험의 도구(resources.py)를 같이 쓴다.

    python3 scripts/experiments/load.py <실험> [--minutes=30]

    sustained     초당 500건, 30분, 지문 1,000종. 대기열 나이가 늘지 않는가, 표시 지연 p95(2.2)
    diversity     지문 10,000종을 고르게. 캐시가 안 맞을 때의 PostgreSQL 부하(1,000종과 비교)
    interference  폭주(초당 5,000건) 중에 콘솔의 30일 이슈 목록 조회. 조회가 수집 응답 시간에 영향을 주는가
    sdk-overhead  데모 앱 두 개(SDK 켬, 끔)에 같은 요청 부하. 추적을 모든 요청에 켠 SDK 의 추가 지연(2.2)

준비
  ./gradlew :loadgen:installDist :demo-api:bootJar && docker compose --profile core --profile obs --profile demo build
  docker compose up -d postgres && (데모와 실험 프로젝트) scripts/demo/seed.sql, scripts/experiments/seed.sql

부하는 SDK 와 같은 모양으로 묶어 보낸다(봉투당 10건, 폭주는 50건, 11.2). 한 봉투에 1건씩 초당 5,000개를 여는 모양은
실제 SDK 에서 나오지 않는다(EXPERIMENTS 의 열린 문제).
"""
import json
import math
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import resources as r  # noqa: E402

ROOT = r.ROOT
DSN = f"http://{r.KEY}@ingest:8080/{r.PROJECT}"
LOADGEN_HOST = ROOT / "loadgen" / "build" / "install" / "loadgen" / "bin" / "loadgen"


def arg(name, default):
    for a in sys.argv[1:]:
        if a.startswith(f"--{name}="):
            return type(default)(a.split("=", 1)[1])
    return default


# ------------------------------------------------------------------ 공통

def start_stack():
    r.up({})
    r.reset_queue()
    r.compose("up", "-d", "--wait", "worker")


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(time.time() - 2))


def counted(start_iso, project=r.PROJECT):
    total = r.clickhouse(f"SELECT sum(times) FROM issue_stats_1m WHERE project_id = {project} "
                         f"AND minute >= toStartOfMinute(parseDateTime64BestEffort('{start_iso}'))")
    return int(total or 0)


def wait_drained(limit_s=600):
    deadline = time.time() + limit_s
    while time.time() < deadline and r.xlen() > 0:
        time.sleep(2)
    time.sleep(10)  # 마지막 묶음이 쓰이고 분 단위 집계에 합쳐질 때까지


def gauge(metrics, name, **labels):
    v = r.pick(metrics, name, **labels)
    return None if v is None or math.isnan(v) else v


def e2e(before, after):
    return {q: r.quantile(before, after, "errdeck_e2e_latency_seconds", q) for q in (0.5, 0.95, 0.99)}


def postgres_stats():
    out = r.sh(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "errdeck", "-At", "-F", ",", "-c",
                "SELECT xact_commit, tup_returned, tup_fetched, tup_inserted, tup_updated, blks_read, blks_hit "
                "FROM pg_stat_database WHERE datname = 'errdeck'"]).strip()
    keys = ["xact_commit", "tup_returned", "tup_fetched", "tup_inserted", "tup_updated", "blks_read", "blks_hit"]
    return dict(zip(keys, map(int, out.split(","))))


def issue_count():
    return int(r.sh(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "errdeck", "-At", "-c",
                     f"SELECT count(*) FROM issues WHERE project_id = {r.PROJECT}"]).strip())


# ------------------------------------------------------------------ 지속 부하

def exp_sustained():
    """초당 500건(봉투 50개, 10건씩)을 정한 시간 동안. 10초마다 대기열 길이와 가장 오래된 항목의 나이를,
    5분마다 worker 의 표시 지연 히스토그램을 읽어 구간별 p95 를 낸다. 구간 p95 가 뒤로 갈수록 늘지 않으면 지연이 쌓이지 않은 것이다."""
    minutes = arg("minutes", 30)
    start_stack()
    start = now_iso()
    baseline = counted(start)
    result = {}

    def load():
        out, _ = r.loadgen("sustained", f"--dsn={DSN}", "--rate=500", f"--duration={minutes}m", "--batch=10",
                           f"--fingerprints={r.FINGERPRINTS}")
        result.update(out)

    t = threading.Thread(target=load)
    first = r.scrape("worker")
    w0 = first
    windows, samples = [], []
    t0 = time.time()
    t.start()
    next_window = t0 + 300
    while t.is_alive():
        time.sleep(10)
        try:
            ing = r.scrape("ingest")
            samples.append({"s": round(time.time() - t0), "queue": r.xlen(),
                            "oldest_age_s": gauge(ing, "errdeck_queue_oldest_age", stream="events")})
        except Exception as e:  # 한 번 못 읽어도 실험은 계속한다
            r.log(f"표본 실패 {e}")
        if time.time() >= next_window:
            w1 = r.scrape("worker")
            windows.append({"until_min": round((time.time() - t0) / 60), **{f"p{int(q * 100)}": v for q, v in e2e(w0, w1).items()}})
            r.log(f"구간 {windows[-1]}, 대기열 {samples[-1] if samples else None}")
            w0, next_window = w1, next_window + 300
    t.join()
    wait_drained()
    last = r.scrape("worker")
    accepted = result.get("codes", {}).get("202", 0) * 10
    stored = counted(start) - baseline
    ages = [s["oldest_age_s"] for s in samples if s["oldest_age_s"] is not None]
    queues = [s["queue"] for s in samples]
    return {
        "minutes": minutes,
        "loadgen": result,
        "accepted_events": accepted,
        "stored_events": stored,
        "e2e_total": e2e(first, last),
        "e2e_windows": windows,
        "queue_max": max(queues) if queues else None,
        "queue_avg": round(sum(queues) / len(queues), 1) if queues else None,
        "oldest_age_max_s": max(ages) if ages else None,
        "oldest_age_last5_avg_s": round(sum(ages[-30:]) / len(ages[-30:]), 2) if ages else None,
        "samples": samples[::6],
    }


# ------------------------------------------------------------------ 지문 다양성

def diversity_run(fingerprints, seconds, cold):
    """worker 를 다시 띄우면(cold) 지문에서 이슈를 찾는 캐시가 빈다. PostgreSQL 의 CPU 와 문장 수를 시작과 끝에서 읽어 뺀다."""
    if cold:
        r.compose("restart", "worker")
        r.compose("up", "-d", "--wait", "worker")
    issues0, pg0, cpu0, w0 = issue_count(), postgres_stats(), r.cpu_stat("postgres"), r.scrape("worker")
    t0 = time.time()
    peak = {"queue": 0}

    def watch(stop):
        while not stop.is_set():
            peak["queue"] = max(peak["queue"], r.xlen())
            stop.wait(2)

    stop = threading.Event()
    watcher = threading.Thread(target=watch, args=(stop,), daemon=True)
    watcher.start()
    out, _ = r.loadgen("sustained", f"--dsn={DSN}", "--rate=500", f"--duration={seconds}s", "--batch=10",
                       f"--fingerprints={fingerprints}")
    wait_drained()
    stop.set()
    wall = time.time() - t0
    issues1, pg1, cpu1, w1 = issue_count(), postgres_stats(), r.cpu_stat("postgres"), r.scrape("worker")
    stages = sorted({dict(ls).get("stage") for (n, ls) in w1 if n == "errdeck_worker_stage_seconds_count"} - {None})
    return {
        "fingerprints": fingerprints,
        "cold_cache": cold,
        "loadgen_codes": out["codes"],
        "new_issues": issues1 - issues0,
        "postgres_cpu_cores": round((cpu1["usage_usec"] - cpu0["usage_usec"]) / 1e6 / wall, 3),
        "postgres_per_s": {k: round((pg1[k] - pg0[k]) / wall, 1) for k in pg0},
        "stage_ms": {st: round(v, 3) for st in stages if (v := r.stage_ms(w0, w1, st)) is not None},
        "e2e": e2e(w0, w1),
        "queue_peak": peak["queue"],
    }


def exp_diversity():
    seconds = arg("seconds", 180)
    start_stack()
    out = {}
    r.log("준비: 지문 1,000종으로 이슈를 만들어 둔다")
    diversity_run(1_000, 60, cold=False)
    for name, fps, cold in [("1,000종, 빈 캐시", 1_000, True), ("10,000종, 빈 캐시(새 이슈 9,000개)", 10_000, True),
                            ("10,000종, 찬 캐시", 10_000, False)]:
        r.log(name)
        out[name] = diversity_run(fps, seconds, cold)
        r.log(json.dumps(out[name], ensure_ascii=False))
    return out


# ------------------------------------------------------------------ 조회 간섭

def exp_interference():
    """같은 폭주(초당 5,000건, 봉투 50건씩 = 초당 봉투 100개)를 조회 없이 한 번, 30일 이슈 목록 조회를 걸고 두 번(초당 5, 20)."""
    seconds = arg("seconds", 120)
    start_stack()
    token = r.api_token()
    url = f"http://api:8081/api/projects/{r.PROJECT}/issues?period=30d"
    r.loadgen("http", f"--url={url}", f"--bearer={token}", "--rate=5", "--duration=10s")  # 예열
    r.loadgen("burst", f"--dsn={DSN}", "--rate=5000", "--duration=15s", "--batch=50")  # 예열
    out = {}
    for name, qps in [("조회 없음", 0), ("조회 초당 5", 5), ("조회 초당 20", 20), ("조회 없음(다시)", 0)]:
        wait_drained()
        r.log(name)
        query = {}
        token = r.api_login()

        def queries():
            res, _ = r.loadgen("http", f"--url={url}", f"--bearer={token}", f"--rate={qps}", f"--duration={seconds}s",
                               "--max-in-flight=200", "--timeout=10s")
            query.update(res)

        ing0, ch0 = r.scrape("ingest"), r.cpu_stat("clickhouse")
        q = threading.Thread(target=queries) if qps else None
        if q:
            q.start()
        burst, _ = r.loadgen("burst", f"--dsn={DSN}", "--rate=5000", f"--duration={seconds}s", "--batch=50")
        if q:
            q.join()
        ing1, ch1 = r.scrape("ingest"), r.cpu_stat("clickhouse")
        out[name] = {
            "burst": burst,
            "ingest_server_p99_s": r.quantile(ing0, ing1, "http_server_requests_seconds", 0.99, uri="/ingest/{projectId}/envelope"),
            "clickhouse_cpu_cores": round((ch1["usage_usec"] - ch0["usage_usec"]) / 1e6 / seconds, 2),
            "query": query or None,
        }
        r.log(json.dumps(out[name], ensure_ascii=False))
    return out


# ------------------------------------------------------------------ SDK 부담

def host_metrics(port):
    with urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=5) as resp:
        text = resp.read().decode()
    out = {}
    for line in text.splitlines():
        if line.startswith("http_server_requests_seconds_bucket"):
            m = re.match(r"([a-zA-Z_:][\w:]*)(\{[^}]*\})?\s+(\S+)", line)
            labels = tuple(sorted(re.findall(r'(\w+)="([^"]*)"', m.group(2) or "")))
            out[(m.group(1), labels)] = float(m.group(3))
    return out


def server_quantile(before, after, uri, q):
    """버킷 안을 선형으로 나눠 분위수를 낸다. 버킷 경계만 쓰면 1ms 차이를 가리지 못한다."""
    buckets = []
    for (n, ls), v in after.items():
        d = dict(ls)
        if d.get("uri") != uri:
            continue
        le = float("inf") if d["le"] == "+Inf" else float(d["le"])
        buckets.append((le, v - before.get((n, ls), 0)))
    buckets.sort()
    if not buckets or buckets[-1][1] <= 0:
        return None
    target, prev_le, prev_c = q * buckets[-1][1], 0.0, 0.0
    for le, c in buckets:
        if c >= target:
            if le == float("inf"):
                return prev_le * 1000
            return round(1000 * (prev_le + (le - prev_le) * (target - prev_c) / max(c - prev_c, 1e-9)), 3)
        prev_le, prev_c = le, c
    return None


def start_demo(port, enabled):
    jar = next((ROOT / "demo-api" / "build" / "libs").glob("demo-api-*[!n].jar"))
    env = dict(os.environ, DEMO_PORT=str(port), DEMO_ERRDECK_ENABLED=str(enabled).lower(),
               DEMO_ERRDECK_DSN=f"http://demoapikey@localhost:{os.environ.get('ERRDECK_INGEST_HOST_PORT', '18080')}/9001",
               DEMO_SCHEDULED_FAILURE_ENABLED="false", DEMO_TRACE_SAMPLE_RATE="1.0",
               DEMO_DOWNSTREAM_URL=f"http://localhost:{port}", DEMO_ENVIRONMENT="experiment")
    r.RUN.mkdir(parents=True, exist_ok=True)
    log = open(r.RUN / f"demo-{port}.log", "w")
    proc = subprocess.Popen(["java", "-Xms256m", "-Xmx256m", "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(120):
        try:
            with urllib.request.urlopen(f"http://localhost:{port}/actuator/health", timeout=2):
                return proc
        except Exception:
            time.sleep(1)
    proc.kill()
    raise RuntimeError(f"데모 앱 {port} 이 뜨지 않았다")


def host_loadgen(url, rate, seconds):
    out = subprocess.run([str(LOADGEN_HOST), "http", f"--url={url}", f"--rate={rate}", f"--duration={seconds}s"],
                         text=True, capture_output=True, timeout=seconds + 120).stdout
    return r.parse_loadgen(out)


def exp_sdk_overhead():
    """같은 jar 를 SDK 켬(18090), 끔(18091)으로 띄우고 같은 맥의 loadgen 으로 번갈아 건다. 켠 쪽은 모든 요청을 추적해
    실제 ingest 로 보낸다(표본 1.0, 실제 기본값 0.1 보다 무겁다). 회차마다 순서를 바꿔 시간에 따른 흔들림을 양쪽에 고루 나눈다.
    클라이언트 쪽 p99(loadgen)와 서버 쪽 p99(데모 앱의 http.server.requests, SDK 필터를 안에 포함)를 함께 적는다."""
    rounds, seconds = arg("rounds", 3), arg("seconds", 60)
    start_stack()
    start = now_iso()
    apps = {"on": (18090, start_demo(18090, True)), "off": (18091, start_demo(18091, False))}
    out = {}
    try:
        for path, uri, rate in [("/demo/ok", "/demo/ok", 500), ("/demo/items/1", "/demo/items/{id}", 300)]:
            for name, (port, _) in apps.items():
                host_loadgen(f"http://localhost:{port}{path}", rate, 20)  # 예열
            rows = []
            for i in range(rounds):
                order = ["off", "on"] if i % 2 == 0 else ["on", "off"]
                row = {}
                for name in order:
                    port = apps[name][0]
                    m0 = host_metrics(port)
                    res = host_loadgen(f"http://localhost:{port}{path}", rate, seconds)
                    m1 = host_metrics(port)
                    row[name] = {"client_p50": res["p50"], "client_p99": res["p99"], "codes": res["codes"], "fails": res["fails"],
                                 "server_p50": server_quantile(m0, m1, uri, 0.5), "server_p99": server_quantile(m0, m1, uri, 0.99)}
                r.log(f"{path} {i + 1}회차 {json.dumps(row, ensure_ascii=False)}")
                rows.append(row)
            out[path] = rows
        time.sleep(15)
        out["transactions_stored"] = int(r.clickhouse(
            f"SELECT count() FROM transactions WHERE project_id = 9001 AND timestamp >= parseDateTime64BestEffort('{start}')") or 0)
        out["demo_on_rss_mb"] = round(int(subprocess.run(["ps", "-o", "rss=", "-p", str(apps["on"][1].pid)],
                                                         text=True, capture_output=True).stdout.strip() or 0) / 1024)
    finally:
        for _, proc in apps.values():
            proc.terminate()
    return out


EXPERIMENTS = {"sustained": exp_sustained, "diversity": exp_diversity, "interference": exp_interference,
               "sdk-overhead": exp_sdk_overhead}


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(names) != 1 or names[0] not in EXPERIMENTS:
        print(__doc__)
        sys.exit(2)
    name = names[0]
    commit = r.sh(["git", "rev-parse", "--short", "HEAD"]).strip()
    started = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"{name} 시작 {started} 커밋 {commit}", flush=True)
    result = {"experiment": name, "commit": commit, "started": started, "values": EXPERIMENTS[name]()}
    r.RUN.mkdir(parents=True, exist_ok=True)
    path = r.RUN / f"{name}-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"결과: {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
