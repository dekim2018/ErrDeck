#!/usr/bin/env python3
"""18.3 의 자원 실험 7종. 변수 하나만 바꾸고 나머지는 고정한다.

    python3 scripts/experiments/resources.py <실험> [--quick]

    compute-threads   worker 계산 풀 크기 1, 2, 4, 8 (CPU 상한 2.0 고정)
    gc                worker 의 GC Parallel, G1, Serial
    batch-rows        ClickHouse 묶음 크기 500, 5,000, 50,000행
    ingest-inflight   ingest 의 Redis 동시 호출 상한 256 과 사실상 없음, Redis 에 멈춤을 주입
    api-pool          api 의 커넥션 풀 4, 10, 30
    compact-headers   worker 의 압축 객체 헤더 켬, 끔
    cores             서버 코어 4개와 2개(17.4 의 오라클 값)

실험은 하나씩 돌린다. 값 하나마다 core 프로필을 그 값으로 다시 띄우고, 끝나면 compose 의 기본값으로 되돌리지 않고 둔다.
마지막에 `docker compose --profile core --profile obs stop` 으로 내린다.

고정하는 것
  - errdeck 컨테이너는 코어 0~3(4코어 서버, 14.1 의 기준)에 묶고, loadgen 은 코어 4~9 에서 돌린다.
  - 처리량은 대기열을 미리 채워 두고 worker 를 띄워 다 비울 때까지의 속도다. worker 는 확인 처리한 항목을 지우므로
    XLEN 이 0 이 되면 끝이다.
  - 프로세스 지표는 Prometheus(15초 수집)가 아니라 컨테이너 안의 관리 포트에서 시작과 끝에 직접 읽어 차이를 낸다.
    CPU 와 제한 구간은 cgroup 의 cpu.stat 에서 읽는다.

준비: ./gradlew :loadgen:installDist 와 docker compose --profile core --profile obs build.
"""
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "scripts" / "experiments" / ".run"
OVERRIDE = RUN / "override.yml"

PROJECT = 3
KEY = "loadgenhigh3"
STACK_CPUS = "0-3"
LOADGEN_CPUS = "4-9"
REDIS = ("127.0.0.1", int(os.environ.get("ERRDECK_REDIS_PORT", "16379")))
API = f"http://localhost:{os.environ.get('ERRDECK_API_HOST_PORT', '18081')}"

# 대기열을 채우는 양. 이벤트 20건씩 봉투 18,000개 = 36만 건. 대기열 상한(봉투 20,000) 안에 둔다.
FILL_ENVELOPES = 18_000
FILL_BATCH = 20
FINGERPRINTS = 1_000

QUICK = "--quick" in sys.argv


# ------------------------------------------------------------------ 실행 도구

def sh(cmd, check=True, capture=True, timeout=None):
    result = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=capture, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"실패: {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result.stdout if capture else ""


def compose(*args, **kw):
    return sh(["docker", "compose", "-f", "docker-compose.yml", "-f", str(OVERRIDE),
               "--profile", "core", "--profile", "obs", "--profile", "demo", *args], **kw)


def yaml_env(env):
    return "".join(f"      {k}: {json.dumps(str(v))}\n" for k, v in env.items())


def write_override(services):
    """services: {서비스: {"env": {...}, "cpus": 1.0, "cpuset": "0-3", "command": "..."}}. 모든 errdeck 서비스를 STACK_CPUS 에 묶는다."""
    names = ["postgres", "redis", "clickhouse", "api", "ingest", "worker"]
    out = ["services:\n"]
    for name in sorted(set(names) | set(services)):
        spec = services.get(name, {})
        out.append(f"  {name}:\n")
        out.append(f"    cpuset: \"{spec.get('cpuset', STACK_CPUS)}\"\n")
        if "cpus" in spec:
            out.append(f"    cpus: {spec['cpus']}\n")
        if "command" in spec:
            out.append(f"    command: {json.dumps(spec['command'])}\n")
        if spec.get("env"):
            out.append("    environment:\n" + yaml_env(spec["env"]))
    out.append(f"  loadgen:\n    cpuset: \"{LOADGEN_CPUS}\"\n")
    RUN.mkdir(parents=True, exist_ok=True)
    OVERRIDE.write_text("".join(out))


def up(services):
    write_override(services)
    compose("up", "-d", "--wait", "--wait-timeout", "240",
            "postgres", "redis", "clickhouse", "api", "ingest", "worker", "prometheus", "grafana", "cadvisor")


def redis(*args):
    """redis-cli 없이 RESP 로 한 명령을 보낸다."""
    with socket.create_connection(REDIS, timeout=10) as s:
        payload = f"*{len(args)}\r\n" + "".join(f"${len(str(a).encode())}\r\n{a}\r\n" for a in args)
        s.sendall(payload.encode())
        return _read_resp(s.makefile("rb"))


def _read_resp(f):
    line = f.readline().rstrip(b"\r\n")
    kind, rest = line[:1], line[1:]
    if kind == b"+":
        return rest.decode()
    if kind == b"-":
        raise RuntimeError(rest.decode())
    if kind == b":":
        return int(rest)
    if kind == b"$":
        n = int(rest)
        if n < 0:
            return None
        data = f.read(n + 2)[:-2]
        return data.decode(errors="replace")
    if kind == b"*":
        n = int(rest)
        return None if n < 0 else [_read_resp(f) for _ in range(n)]
    raise RuntimeError(f"모르는 응답: {line!r}")


def xlen(stream="events"):
    return redis("XLEN", f"errdeck:stream:{stream}")


def reset_queue():
    """worker 를 멈추고 대기열과 중복 확인 키를 비운다. 그룹은 worker 가 다시 띄울 때 0 부터 만든다."""
    compose("stop", "worker")
    redis("DEL", "errdeck:stream:events", "errdeck:stream:tx")
    cursor = "0"
    while True:
        cursor, keys = redis("SCAN", cursor, "MATCH", "errdeck:seen:*", "COUNT", "10000")
        if keys:
            redis("UNLINK", *keys)
        if cursor == "0":
            break


def loadgen(*args):
    out = compose("run", "--rm", "-T", "loadgen", *args, timeout=1800)
    return parse_loadgen(out), out


def parse_loadgen(out):
    codes = {m.group(1): int(m.group(2).replace(",", "")) for m in re.finditer(r"^\s+(\d{3})\s+([\d,]+)", out, re.M)}
    lat = re.search(r"p50 ([\d.,]+)ms, p99 ([\d.,]+)ms, 최대 ([\d.,]+)ms", out)
    fails = {k: int(v.replace(",", "")) for k, v in re.findall(r"^\s+(제한 시간|연결 실패|보내지 않음)\s+([\d,]+)", out, re.M)}
    return {
        "codes": codes,
        "p50": float(lat.group(1).replace(",", "")) if lat else None,
        "p99": float(lat.group(2).replace(",", "")) if lat else None,
        "max": float(lat.group(3).replace(",", "")) if lat else None,
        "fails": fails,
    }


def fill(envelopes=FILL_ENVELOPES, batch=FILL_BATCH, per_second=300):
    """worker 를 멈춘 채로 대기열을 채운다. 막 뜬 ingest(1코어)는 JIT 컴파일 동안 느리므로 10초 동안 천천히 데운 뒤 채운다.
    채우는 속도는 ingest 가 버티는 초당 봉투 300개다(17 작업에서 잰 값)."""
    loadgen("sustained", f"--dsn=http://{KEY}@ingest:8080/{PROJECT}", f"--rate={50 * batch}", "--duration=10s",
            f"--batch={batch}", f"--fingerprints={FINGERPRINTS}")
    seconds = max(1, (envelopes - 500) // per_second)
    rate = per_second * batch
    result, _ = loadgen("sustained", f"--dsn=http://{KEY}@ingest:8080/{PROJECT}", f"--rate={rate}",
                        f"--duration={seconds}s", f"--batch={batch}", f"--fingerprints={FINGERPRINTS}")
    return result


# ------------------------------------------------------------------ 지표

HEALTH = "exec 3<>/dev/tcp/127.0.0.1/8091 && printf 'GET {path} HTTP/1.0\\r\\n\\r\\n' >&3 && cat <&3"


def scrape(service):
    """컨테이너 안의 관리 포트에서 프로메테우스 텍스트를 읽어 {(이름, 라벨들): 값} 으로."""
    text = compose("exec", "-T", service, "bash", "-c", HEALTH.format(path="/actuator/prometheus"))
    out = {}
    for line in text.splitlines():
        if not line or line.startswith("#") or not line.startswith(("errdeck", "jvm", "hikari", "http", "process", "executor")):
            continue
        m = re.match(r"([a-zA-Z_:][\w:]*)(\{[^}]*\})?\s+(\S+)", line)
        if m:
            labels = tuple(sorted(re.findall(r'(\w+)="([^"]*)"', m.group(2) or "")))
            out[(m.group(1), labels)] = float(m.group(3))
    return out


def cpu_stat(service):
    text = compose("exec", "-T", service, "cat", "/sys/fs/cgroup/cpu.stat")
    return {k: int(v) for k, v in (line.split() for line in text.splitlines())}


def pick(metrics, name, **labels):
    return sum(v for (n, ls), v in metrics.items() if n == name and all((k, str(val)) in ls for k, val in labels.items()))


def delta(before, after, name, **labels):
    return pick(after, name, **labels) - pick(before, name, **labels)


def stage_ms(before, after, stage):
    n = delta(before, after, "errdeck_worker_stage_seconds_count", stage=stage)
    return 1000 * delta(before, after, "errdeck_worker_stage_seconds_sum", stage=stage) / n if n else None


def quantile(before, after, name, q, **labels):
    """누적 히스토그램 버킷의 차이로 분위수를 구한다(버킷 경계값으로 근사)."""
    buckets = []
    for (n, ls), v in after.items():
        if n != f"{name}_bucket" or not all((k, str(val)) in ls for k, val in labels.items()):
            continue
        le = dict(ls)["le"]
        buckets.append((float("inf") if le == "+Inf" else float(le), v - before.get((n, ls), 0)))
    buckets.sort()
    if not buckets or buckets[-1][1] <= 0:
        return None
    target = q * buckets[-1][1]
    for le, count in buckets:
        if count >= target:
            return le
    return None


def heap_used(metrics):
    return pick(metrics, "jvm_memory_used_bytes", area="heap")


class Sampler(threading.Thread):
    """지표를 주기적으로 읽어 최대값과 평균을 남긴다."""

    def __init__(self, service, fn, every=1.0):
        super().__init__(daemon=True)
        self.service, self.fn, self.every = service, fn, every
        self.values = []
        self.stop_flag = threading.Event()

    def run(self):
        while not self.stop_flag.is_set():
            try:
                self.values.append(self.fn(scrape(self.service)))
            except Exception:
                pass
            self.stop_flag.wait(self.every)

    def stop(self):
        self.stop_flag.set()
        self.join()
        return {"max": max(self.values) if self.values else None,
                "avg": sum(self.values) / len(self.values) if self.values else None}


# ------------------------------------------------------------------ 비우기 측정

def drain(sample_heap=False, timeout=900):
    """대기열을 채운 상태에서 worker 를 띄우고 다 비울 때까지 잰다.

    처리량은 남은 봉투가 90% 에서 10% 로 줄어든 구간으로 잰다. JVM 이 뜨고 데워지는 앞부분과 끝의 꼬리를 뺀다.
    시작 시점의 지표는 worker 가 건강해진 뒤에 읽으므로 그 사이에 처리한 것은 단계 시간과 GC 에 들어가지 않는다.
    """
    initial = xlen()
    trace = []
    done = threading.Event()

    def poll():
        deadline = time.time() + timeout
        while time.time() < deadline:
            left = xlen()
            trace.append((time.time(), left))
            if left == 0:
                break
            # 60초 동안 하나도 줄지 않으면 멈춘 것으로 보고 끝낸다.
            stalled = [l for t, l in trace if t > time.time() - 60]
            if len(trace) > 240 and stalled and min(stalled) == max(stalled) and stalled[0] == left:
                log(f"60초 동안 {left}개에서 줄지 않아 멈춘다")
                break
            time.sleep(0.25)
        done.set()

    poller = threading.Thread(target=poll, daemon=True)
    poller.start()
    compose("up", "-d", "--wait", "worker")
    before, cpu0, t_ready = scrape("worker"), cpu_stat("worker"), time.time()
    sampler = Sampler("worker", heap_used) if sample_heap else None
    if sampler:
        sampler.start()
    done.wait()
    after, cpu1, t_end = scrape("worker"), cpu_stat("worker"), time.time()
    heap = sampler.stop() if sampler else None

    def crossed(fraction):
        return next((t for t, left in trace if left <= initial * fraction), None)

    t90, t10, t_first = crossed(0.9), crossed(0.1), crossed(0.999)
    window = (t10 - t90) if t90 and t10 and t10 > t90 else None
    wall = t_end - t_ready or 1
    periods = cpu1["nr_periods"] - cpu0["nr_periods"]
    return {
        "envelopes": initial,
        "left": trace[-1][1] if trace else None,
        "total_s": round(trace[-1][0] - t_first, 1) if t_first and trace else None,
        "events_per_s": round(initial * 0.8 * FILL_BATCH / window) if window else None,
        "cpu_cores": round((cpu1["usage_usec"] - cpu0["usage_usec"]) / 1e6 / wall, 2),
        "throttled_ratio": round((cpu1["nr_throttled"] - cpu0["nr_throttled"]) / periods, 3) if periods else None,
        "throttled_s": round((cpu1["throttled_usec"] - cpu0["throttled_usec"]) / 1e6, 1),
        "stage_ms": {st: round(v, 3) for st in ("decompress", "restore", "fingerprint", "write")
                     if (v := stage_ms(before, after, st)) is not None},
        "gc_pause_s": round(delta(before, after, "jvm_gc_pause_seconds_sum"), 2),
        "gc_pause_max_ms": round(1000 * max([v for (n, _), v in after.items() if n == "jvm_gc_pause_seconds_max"] or [0]), 1),
        "gc_count": int(delta(before, after, "jvm_gc_pause_seconds_count")),
        "batch_rows_avg": round(delta(before, after, "errdeck_clickhouse_batch_rows_sum", table="events")
                                / max(1, delta(before, after, "errdeck_clickhouse_batch_rows_count", table="events"))),
        "ticks_rows_avg": round(delta(before, after, "errdeck_clickhouse_batch_rows_sum", table="event_ticks")
                                / max(1, delta(before, after, "errdeck_clickhouse_batch_rows_count", table="event_ticks"))),
        "flush_ms_avg": round(1000 * delta(before, after, "errdeck_clickhouse_flush_seconds_sum")
                              / max(1, delta(before, after, "errdeck_clickhouse_flush_seconds_count")), 1),
        "heap_mb": {k: round(v / 1048576) for k, v in heap.items() if v is not None} if heap else None,
        "live_mb": round(pick(after, "jvm_gc_live_data_size_bytes") / 1048576),
    }


def steady_e2e(rate=1_000, seconds=60, batch=10):
    """worker 가 따라가는 부하를 걸고 수신부터 저장까지 걸린 시간을 잰다."""
    before = scrape("worker")
    result, _ = loadgen("sustained", f"--dsn=http://{KEY}@ingest:8080/{PROJECT}", f"--rate={rate}",
                        f"--duration={seconds}s", f"--batch={batch}", f"--fingerprints={FINGERPRINTS}")
    time.sleep(5)
    after = scrape("worker")
    return {
        "ingest": result,
        "e2e_p50_s": quantile(before, after, "errdeck_e2e_latency_seconds", 0.5),
        "e2e_p95_s": quantile(before, after, "errdeck_e2e_latency_seconds", 0.95),
        "queue_left": xlen(),
    }


def clickhouse(query):
    return sh(["docker", "compose", "exec", "-T", "clickhouse", "clickhouse-client", "--user", "errdeck",
               "--password", "errdeck", "-d", "errdeck", "-q", query]).strip()


# ------------------------------------------------------------------ 실험

WORKER_OPTS = "-XX:ActiveProcessorCount=2 -Xms448m -Xmx448m {gc} {coh} -XX:+ExitOnOutOfMemoryError"


def worker_opts(gc="-XX:+UseParallelGC", coh="-XX:+UseCompactObjectHeaders"):
    return WORKER_OPTS.format(gc=gc, coh=coh)


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def drain_run(services, **kw):
    log(f"띄우기 {json.dumps(services, ensure_ascii=False)}")
    up(services)
    reset_queue()
    log("채우기")
    filled = fill()
    log(f"채움 {xlen()}개 {filled['codes']}")
    result = drain(**kw)
    log(f"비움 {result['total_s']}초, 초당 {result['events_per_s']}건")
    result["fill"] = filled
    return result


def exp_compute_threads():
    values = [1, 2] if QUICK else [1, 2, 4, 8]
    return {n: drain_run({"worker": {"env": {"ERRDECK_WORKER_COMPUTETHREADS": n}}}) for n in values}


def exp_gc():
    gcs = {"Parallel": "-XX:+UseParallelGC", "G1": "-XX:+UseG1GC", "Serial": "-XX:+UseSerialGC"}
    return {name: drain_run({"worker": {"env": {"JAVA_OPTS": worker_opts(gc=flag)}}}, sample_heap=True)
            for name, flag in gcs.items()}


def exp_batch_rows():
    out = {}
    for rows in [500, 5_000, 50_000]:
        r = drain_run({"worker": {"env": {"ERRDECK_WORKER_BATCHMAXROWS": rows}}})
        r["parts_after_drain"] = clickhouse(
            "SELECT max(c) FROM (SELECT count() c FROM system.parts WHERE database='errdeck' AND active GROUP BY table, partition)")
        r["steady"] = steady_e2e()
        out[rows] = r
    return out


def exp_ingest_inflight():
    """worker 를 멈춘 채로 ingest 에 초당 1,000 요청을 걸고, Redis 를 2초마다 1초씩 멈춘다."""
    out = {}
    for name, limit in [("256", 256), ("없음", 1_000_000)]:
        up({"ingest": {"env": {"ERRDECK_INGEST_REDIS_MAXINFLIGHT": limit, "ERRDECK_INGEST_QUEUE_EVENTSMAXLENGTH": 200_000}},
            "redis": {"command": "redis-server --appendonly yes --appendfsync everysec --maxmemory 256mb "
                                 "--maxmemory-policy noeviction --enable-debug-command local"}})
        reset_queue()
        before = scrape("ingest")
        sampler = Sampler("ingest", heap_used, every=0.5)
        sampler.start()
        stop = threading.Event()

        def stall():
            time.sleep(10)
            while not stop.is_set():
                sh(["docker", "compose", "exec", "-T", "redis", "redis-cli", "DEBUG", "SLEEP", "1"], check=False)
                stop.wait(1)

        staller = threading.Thread(target=stall, daemon=True)
        staller.start()
        result, _ = loadgen("sustained", f"--dsn=http://{KEY}@ingest:8080/{PROJECT}", "--rate=1000", "--duration=40s",
                            "--batch=1", f"--fingerprints={FINGERPRINTS}", "--max-in-flight=5000")
        stop.set()
        staller.join()
        heap = sampler.stop()
        after = scrape("ingest")
        accepted = result["codes"].get("202", 0)
        out[name] = {
            "loadgen": result,
            "heap_mb": {k: round(v / 1048576) for k, v in heap.items() if v is not None},
            "stream_len": xlen(),
            "accepted": accepted,
            "enqueued_but_not_202": xlen() - accepted,
            "unavailable": int(delta(before, after, "errdeck_ingest_requests_total", result="unavailable")),
            "threads_peak": int(pick(after, "jvm_threads_peak_threads")),
        }
    return out


def api_token():
    """실험용 사용자를 만들고 프로젝트 3 의 시스템에 VIEWER 로 넣는다."""
    import urllib.request
    email = "experiment@errdeck.local"
    body = json.dumps({"email": email, "password": "experiment-password-1"}).encode()
    for path in ("/api/auth/signup", "/api/auth/login"):
        req = urllib.request.Request(API + path, body, {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as resp:
                data = json.load(resp)
        except urllib.error.HTTPError:
            continue
    sh(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "errdeck", "-c",
        "INSERT INTO system_members (system_id, user_id, role) SELECT p.system_id, u.id, 'VIEWER' FROM projects p, users u "
        f"WHERE p.id = {PROJECT} AND u.email = '{email}' ON CONFLICT DO NOTHING"])
    return data["accessToken"]


def api_login():
    import urllib.request
    body = json.dumps({"email": "experiment@errdeck.local", "password": "experiment-password-1"}).encode()
    req = urllib.request.Request(API + "/api/auth/login", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)["accessToken"]


def exp_api_pool():
    """같은 조회(이슈 목록)를 초당 300번 30초. 풀 크기만 바꾼다."""
    out = {}
    up({})
    api_token()  # 사용자와 권한은 풀 크기를 바꾸기 전에 만든다. 풀 30 이면 psql 이 들어갈 자리가 없다.
    for size in [4, 10, 30]:
      try:
        log(f"api 풀 {size}")
        up({"api": {"env": {"SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE": size}}})
        token = api_login()
        url = f"http://api:8081/api/projects/{PROJECT}/issues?period=24h"
        loadgen("http", f"--url={url}", f"--bearer={token}", "--rate=100", "--duration=10s")  # 예열
        before, pg0 = scrape("api"), cpu_stat("postgres")
        sampler = Sampler("api", lambda m: pick(m, "hikaricp_connections_pending"), every=0.5)
        sampler.start()
        t0 = time.time()
        result, _ = loadgen("http", f"--url={url}", f"--bearer={token}", "--rate=300", "--duration=30s", "--max-in-flight=2000")
        wall = time.time() - t0
        pending = sampler.stop()
        after, pg1 = scrape("api"), cpu_stat("postgres")
        ok = result["codes"].get("200", 0)
        out[size] = {
            "loadgen": result,
            "ok_per_s": round(ok / 30),
            "pending_max": pending["max"],
            "acquire_ms_avg": round(1000 * delta(before, after, "hikaricp_connections_acquire_seconds_sum")
                                    / max(1, delta(before, after, "hikaricp_connections_acquire_seconds_count")), 2),
            "postgres_cpu_cores": round((pg1["usage_usec"] - pg0["usage_usec"]) / 1e6 / wall, 2),
        }
        log(f"api 풀 {size}: {out[size]['loadgen']['codes']} p99 {out[size]['loadgen']['p99']}ms")
      except Exception as e:
        out[size] = {"error": str(e)[-400:]}
        log(f"api 풀 {size} 실패: {str(e)[-200:]}")
    return out


def exp_compact_headers():
    return {name: drain_run({"worker": {"env": {"JAVA_OPTS": worker_opts(coh=flag)}}}, sample_heap=True)
            for name, flag in [("켬", "-XX:+UseCompactObjectHeaders"), ("끔", "-XX:-UseCompactObjectHeaders")]}


def exp_cores():
    four = {}
    two = {
        "postgres": {"cpuset": "0-1", "cpus": 0.5},
        "redis": {"cpuset": "0-1", "cpus": 0.5},
        "clickhouse": {"cpuset": "0-1", "cpus": 1.0},
        "api": {"cpuset": "0-1", "cpus": 0.5},
        "ingest": {"cpuset": "0-1", "cpus": 0.5},
        "worker": {"cpuset": "0-1", "cpus": 1.0, "env": {
            "ERRDECK_WORKER_COMPUTETHREADS": 1,
            "JAVA_OPTS": "-XX:ActiveProcessorCount=1 -Xms448m -Xmx448m -XX:+UseParallelGC -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError"}},
    }
    out = {}
    for name, services in [("4코어", four), ("2코어(17.4)", two)]:
        r = drain_run(services)
        r["steady"] = steady_e2e(rate=500)
        out[name] = r
    return out


EXPERIMENTS = {
    "compute-threads": exp_compute_threads,
    "gc": exp_gc,
    "batch-rows": exp_batch_rows,
    "ingest-inflight": exp_ingest_inflight,
    "api-pool": exp_api_pool,
    "compact-headers": exp_compact_headers,
    "cores": exp_cores,
}


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(names) != 1 or names[0] not in EXPERIMENTS:
        print(__doc__)
        sys.exit(2)
    name = names[0]
    commit = sh(["git", "rev-parse", "--short", "HEAD"]).strip()
    started = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"{name} 시작 {started} 커밋 {commit}", flush=True)
    result = {"experiment": name, "commit": commit, "started": started, "values": EXPERIMENTS[name]()}
    RUN.mkdir(parents=True, exist_ok=True)
    path = RUN / f"{name}-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"결과: {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
