#!/usr/bin/env python3
"""13.5 의 대시보드 5개를 만든다. 대시보드는 이 파일이 원본이고 dashboards/*.json 은 결과다.

    python3 deploy/grafana/generate.py

패널을 고치면 이 파일을 고치고 다시 만든다. 그라파나 화면에서 고친 것은 저장되지 않는다.
"""
import json
from pathlib import Path

OUT = Path(__file__).parent / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}
APPS = "ingest|worker|api"


class Layout:
    """패널을 왼쪽에서 오른쪽으로, 줄이 차면 아래로 놓는다. 한 줄은 24칸이다."""

    def __init__(self):
        self.x = 0
        self.y = 0
        self.row_h = 0
        self.panels = []

    def add(self, panel, w, h):
        if self.x + w > 24:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": w, "h": h}
        panel["id"] = len(self.panels) + 1
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)

    def row(self, title):
        if self.x:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        self.add({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)
        self.x, self.y, self.row_h = 0, self.y + 1, 0


def targets(exprs):
    out = []
    for i, e in enumerate(exprs):
        expr, legend = e if isinstance(e, tuple) else (e, "")
        out.append({"datasource": DS, "expr": expr, "legendFormat": legend, "refId": chr(65 + i)})
    return out


def timeseries(title, exprs, unit="short", description="", stack=False, thresholds=None):
    defaults = {"unit": unit, "custom": {"fillOpacity": 10, "showPoints": "never"}}
    if stack:
        defaults["custom"]["stacking"] = {"mode": "normal"}
    if thresholds:
        defaults["thresholds"] = steps(thresholds)
        defaults["custom"]["thresholdsStyle"] = {"mode": "line"}
    return {
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": DS,
        "targets": targets(exprs),
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
    }


def stat(title, expr, unit="short", thresholds=None, description=""):
    return {
        "type": "stat",
        "title": title,
        "description": description,
        "datasource": DS,
        "targets": targets([expr]),
        "fieldConfig": {"defaults": {"unit": unit, "thresholds": steps(thresholds or [])}, "overrides": []},
        "options": {"colorMode": "background", "graphMode": "area", "reduceOptions": {"calcs": ["lastNotNull"]}},
    }


def steps(thresholds):
    """[(값, 색)] 을 그라파나 형식으로. 첫 칸은 기준 아래의 색이다."""
    out = [{"color": "green", "value": None}]
    for value, color in thresholds:
        out.append({"color": color, "value": value})
    return {"mode": "absolute", "steps": out}


def variable(name, query, label, multi=False, include_all=False):
    return {
        "name": name,
        "label": label,
        "type": "query",
        "datasource": DS,
        "query": {"query": query, "refId": "v"},
        "definition": query,
        "refresh": 2,
        "multi": multi,
        "includeAll": include_all,
        "current": {},
        "sort": 1,
    }


def dashboard(uid, title, layout, variables=(), description=""):
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": ["errdeck"],
        "timezone": "browser",
        "schemaVersion": 41,
        "refresh": "30s",
        "time": {"from": "now-1h", "to": "now"},
        "templating": {"list": list(variables)},
        "panels": layout.panels,
        "editable": False,
    }


def p95(metric, by, selector=""):
    return f"histogram_quantile(0.95, sum by (le, {by}) (rate({metric}_bucket{{{selector}}}[$__rate_interval])))"


def overview():
    """첫 줄은 2.2 의 목표와 지금 값. 지금 목표를 지키고 있는가."""
    l = Layout()
    l.row("2.2 의 목표")
    ingest = 'job="ingest", uri="/ingest/{projectId}/envelope"'
    l.add(stat("수집 응답 p99 (목표 50ms)",
               f"histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{ingest}}}[5m])))",
               "s", [(0.05, "red")]), 6, 5)
    l.add(stat("표시 지연 p95 (목표 5초)",
               "histogram_quantile(0.95, sum by (le) (rate(errdeck_e2e_latency_seconds_bucket[5m])))",
               "s", [(5, "red")]), 6, 5)
    l.add(stat("거절 비율",
               'sum(rate(errdeck_ingest_requests_total{result!="accepted"}[5m])) / sum(rate(errdeck_ingest_requests_total[5m]))',
               "percentunit", [(0.01, "orange"), (0.05, "red")],
               "429, 503, 413 등 수락하지 못한 요청의 비율"), 6, 5)
    l.add(stat("대기열 최대 나이", "max(errdeck_queue_oldest_age_seconds)", "s", [(10, "orange"), (30, "red")]), 6, 5)

    l.row("지금 상태")
    l.add(stat("살아 있는 프로세스", f'sum(up{{job=~"{APPS}"}})', "short", None,
               "ingest, worker, api 중 응답하는 수. 3 이 정상"), 4, 4)
    l.add(stat("울리는 경보", 'count(ALERTS{alertstate="firing"}) or vector(0)', "short", [(1, "red")]), 4, 4)
    l.add(stat("초당 수신", 'sum(rate(errdeck_ingest_requests_total{result="accepted"}[5m]))', "reqps"), 4, 4)
    l.add(stat("초당 처리", "sum(rate(errdeck_events_processed_total[5m]))", "ops"), 4, 4)
    l.add(stat("격리(1시간)", 'sum(increase(errdeck_events_processed_total{outcome="quarantined"}[1h]))', "short",
               [(1, "orange")]), 4, 4)
    l.add(stat("메모리 합계 / 예산 5,632MB",
               'sum(container_memory_working_set_bytes{service!=""}) / (5632 * 1024 * 1024)', "percentunit",
               [(0.8, "orange"), (0.95, "red")]), 4, 4)

    l.row("추이")
    l.add(timeseries("수집 결과", [('sum by (result) (rate(errdeck_ingest_requests_total[$__rate_interval]))', "{{result}}")],
                     "reqps", stack=True), 12, 8)
    l.add(timeseries("표시 지연 p95 와 p50", [
        ("histogram_quantile(0.95, sum by (le) (rate(errdeck_e2e_latency_seconds_bucket[$__rate_interval])))", "p95"),
        ("histogram_quantile(0.5, sum by (le) (rate(errdeck_e2e_latency_seconds_bucket[$__rate_interval])))", "p50"),
    ], "s", thresholds=[(5, "red")]), 12, 8)
    return dashboard("errdeck-overview", "ErrDeck 전체 개요", l, description="13.5 전체 개요. 지금 목표를 지키고 있는가")


def pipeline():
    """어느 단계가 병목인가."""
    l = Layout()
    l.row("들어오고 나가는 양")
    l.add(timeseries("초당 수신(결과별)", [('sum by (result) (rate(errdeck_ingest_requests_total[$__rate_interval]))', "{{result}}")],
                     "reqps", stack=True), 12, 8)
    l.add(timeseries("초당 처리(결과별)", [('sum by (outcome) (rate(errdeck_events_processed_total[$__rate_interval]))', "{{outcome}}")],
                     "ops", stack=True), 12, 8)

    l.row("대기열")
    l.add(timeseries("대기열 나이", [("max by (stream) (errdeck_queue_oldest_age_seconds)", "{{stream}}")], "s",
                     "길이보다 나이가 중요하다. 30초는 언제나 표시가 30초 늦다는 뜻(13.4)", thresholds=[(30, "red")]), 12, 8)
    l.add(timeseries("대기열 길이", [("max by (stream) (errdeck_queue_length)", "{{stream}}")], "short",
                     thresholds=[(20000, "red")]), 12, 8)

    l.row("worker 단계")
    l.add(timeseries("단계별 평균 시간(봉투 하나, 묶음 하나)", [(
        "sum by (stage) (rate(errdeck_worker_stage_seconds_sum[$__rate_interval])) / sum by (stage) (rate(errdeck_worker_stage_seconds_count[$__rate_interval]))",
        "{{stage}}")], "s"), 12, 8)
    l.add(timeseries("단계가 쓴 시간의 비율(1 = 스레드 하나가 쉼 없이)",
                     [("sum by (stage) (rate(errdeck_worker_stage_seconds_sum[$__rate_interval]))", "{{stage}}")], "short",
                     "계산 단계(decompress, restore, fingerprint)의 합이 계산 풀 크기 2 에 닿으면 계산이 병목", stack=True), 12, 8)
    l.add(timeseries("표시 지연", [
        ("histogram_quantile(0.95, sum by (le) (rate(errdeck_e2e_latency_seconds_bucket[$__rate_interval])))", "p95"),
        ("histogram_quantile(0.99, sum by (le) (rate(errdeck_e2e_latency_seconds_bucket[$__rate_interval])))", "p99"),
    ], "s", thresholds=[(5, "red")]), 12, 8)
    l.add(timeseries("소스맵 캐시", [('sum by (result) (rate(errdeck_sourcemap_cache_total[$__rate_interval]))', "{{result}}")],
                     "ops", "hit 적중, load 볼륨에서 읽어 해석, missing 소스맵 없음", stack=True), 12, 8)

    l.row("ClickHouse 쓰기")
    l.add(timeseries("묶음 크기(평균 행 수)", [(
        "sum by (table) (rate(errdeck_clickhouse_batch_rows_sum[$__rate_interval])) / sum by (table) (rate(errdeck_clickhouse_batch_rows_count[$__rate_interval]))",
        "{{table}}")], "short"), 12, 8)
    l.add(timeseries("묶음 쓰기 시간(평균)", [(
        "sum by (table) (rate(errdeck_clickhouse_flush_seconds_sum[$__rate_interval])) / sum by (table) (rate(errdeck_clickhouse_flush_seconds_count[$__rate_interval]))",
        "{{table}}")], "s"), 12, 8)

    l.row("ingest")
    l.add(timeseries("수집 응답 시간", [
        (p95("http_server_requests_seconds", "job", 'job="ingest", uri="/ingest/{projectId}/envelope"'), "p95"),
        ("histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"ingest\", uri=\"/ingest/{projectId}/envelope\"}[$__rate_interval])))", "p99"),
    ], "s", thresholds=[(0.05, "red")]), 12, 8)
    l.add(timeseries("봉투 크기(평균)", [(
        "rate(errdeck_ingest_envelope_bytes_sum[$__rate_interval]) / rate(errdeck_ingest_envelope_bytes_count[$__rate_interval])",
        "평균")], "bytes", "대기열 메모리 계산의 근거(13.4)"), 12, 8)
    return dashboard("errdeck-pipeline", "ErrDeck 수집 파이프라인", l, description="13.5 수집 파이프라인. 어느 단계가 병목인가")


def jvm():
    """14장의 풀 크기가 맞는가. 서비스 이름을 변수로 받아 팬파라이브, CoPage 도 같이 쓴다."""
    l = Layout()
    sel = 'job="$job"'
    l.row("힙과 GC")
    l.add(timeseries("힙", [
        (f'sum by (instance) (jvm_memory_used_bytes{{{sel}, area="heap"}})', "사용"),
        (f'sum by (instance) (jvm_memory_committed_bytes{{{sel}, area="heap"}})', "확보"),
        (f'sum by (instance) (jvm_memory_max_bytes{{{sel}, area="heap"}} > 0)', "최대"),
    ], "bytes"), 12, 8)
    l.add(timeseries("힙 밖", [(f'sum by (id) (jvm_memory_used_bytes{{{sel}, area="nonheap"}})', "{{id}}")], "bytes",
                     "클래스 정보, 컴파일된 코드. 컨테이너 상한과 힙의 차이를 여기서 쓴다(14.7)", stack=True), 12, 8)
    l.add(timeseries("GC 멈춤 시간(초당)", [(f'sum by (gc, cause) (rate(jvm_gc_pause_seconds_sum{{{sel}}}[$__rate_interval]))', "{{gc}} {{cause}}")],
                     "s"), 12, 8)
    l.add(timeseries("GC 최대 멈춤", [(f'max by (gc) (jvm_gc_pause_seconds_max{{{sel}}})', "{{gc}}")], "s"), 12, 8)

    l.row("스레드와 CPU")
    l.add(timeseries("스레드", [
        (f'sum by (state) (jvm_threads_states_threads{{{sel}}})', "{{state}}"),
    ], "short", stack=True), 12, 8)
    l.add(timeseries("프로세스 CPU(코어)", [
        (f'process_cpu_usage{{{sel}}} * system_cpu_count{{{sel}}}', "사용"),
        (f'system_cpu_count{{{sel}}}', "JVM 이 보는 코어 수"),
    ], "short", "JVM 이 보는 코어 수는 ActiveProcessorCount 로 정한 값이다(14.2)"), 12, 8)
    l.add(timeseries("실행기 풀", [
        (f'sum by (name) (executor_active_threads{{{sel}}})', "{{name}} 활성"),
        (f'sum by (name) (executor_queued_tasks{{{sel}}})', "{{name}} 대기"),
    ], "short"), 12, 8)
    l.add(timeseries("가상 스레드 붙잡힘", [
        (f'sum(rate(jvm_threads_virtual_pinned_seconds_count{{{sel}}}[$__rate_interval]))', "초당 횟수"),
    ], "short", "실행 스레드를 붙잡은 가상 스레드(14.4). JDK 24 부터 synchronized 는 원인이 아니다"), 12, 8)

    l.row("커넥션 풀")
    l.add(timeseries("PostgreSQL 커넥션 풀", [
        (f'sum by (pool) (hikaricp_connections_active{{{sel}}})', "{{pool}} 사용"),
        (f'sum by (pool) (hikaricp_connections_idle{{{sel}}})', "{{pool}} 쉼"),
        (f'sum by (pool) (hikaricp_connections_pending{{{sel}}})', "{{pool}} 대기"),
    ], "short", "대기가 0 보다 크면 풀이 작거나 쿼리가 느리다(13.6)"), 12, 8)
    l.add(timeseries("커넥션 획득 시간(최대)", [(f'max by (pool) (hikaricp_connections_acquire_seconds_max{{{sel}}})', "{{pool}}")],
                     "s"), 12, 8)

    l.row("HTTP")
    l.add(timeseries("요청 수(경로별)", [(f'sum by (uri) (rate(http_server_requests_seconds_count{{{sel}}}[$__rate_interval]))', "{{uri}}")],
                     "reqps"), 12, 8)
    l.add(timeseries("응답 시간 p95(경로별)", [(p95("http_server_requests_seconds", "uri", sel), "{{uri}}")], "s"), 12, 8)
    job = variable("job", "label_values(jvm_info, job)", "서비스")
    return dashboard("errdeck-jvm", "JVM 과 스레드", l, [job], "13.5 JVM 과 스레드. 14장의 풀 크기가 맞는가. 12.4 의 링크가 이 대시보드다")


def containers():
    """14장의 상한이 맞는가."""
    l = Layout()
    sel = 'service=~"$service"'
    l.row("CPU")
    l.add(timeseries("CPU 사용(코어)", [(f'sum by (service) (rate(container_cpu_usage_seconds_total{{{sel}}}[$__rate_interval]))', "{{service}}")],
                     "short"), 12, 8)
    l.add(timeseries("CPU 사용 / 상한", [(
        f'sum by (service) (rate(container_cpu_usage_seconds_total{{{sel}}}[$__rate_interval])) / on (service) (max by (service) (container_spec_cpu_quota{{{sel}}}) / max by (service) (container_spec_cpu_period{{{sel}}}))',
        "{{service}}")], "percentunit", thresholds=[(0.9, "red")]), 12, 8)
    l.add(timeseries("CPU 제한 구간 비율", [(
        f'sum by (service) (rate(container_cpu_cfs_throttled_periods_total{{{sel}}}[$__rate_interval])) / sum by (service) (rate(container_cpu_cfs_periods_total{{{sel}}}[$__rate_interval]))',
        "{{service}}")], "percentunit", "100ms 할당량을 다 써서 멈춘 구간의 비율. 25% 를 넘으면 경보(13.6, 14.2)",
        thresholds=[(0.25, "red")]), 24, 8)

    l.row("메모리")
    l.add(timeseries("메모리 사용(작업 집합)", [(f'sum by (service) (container_memory_working_set_bytes{{{sel}}})', "{{service}}")],
                     "bytes"), 12, 8)
    l.add(timeseries("메모리 사용 / 상한", [(
        f'sum by (service) (container_memory_working_set_bytes{{{sel}}}) / on (service) max by (service) (container_spec_memory_limit_bytes{{{sel}}} > 0)',
        "{{service}}")], "percentunit", thresholds=[(0.9, "red")]), 12, 8)
    l.add(timeseries("메모리 합계 / 예산 5,632MB",
                     [(f'sum(container_memory_working_set_bytes{{service!=""}})', "합계"), ("5632 * 1024 * 1024", "예산")],
                     "bytes"), 12, 8)
    l.add(timeseries("메모리 부족 종료", [(f'sum by (service) (increase(container_oom_events_total{{{sel}}}[$__rate_interval]))', "{{service}}")],
                     "short"), 12, 8)

    l.row("서버")
    l.add(timeseries("서버 CPU", [('1 - avg(rate(node_cpu_seconds_total{mode="idle"}[$__rate_interval]))', "사용률")],
                     "percentunit"), 8, 8)
    l.add(timeseries("서버 메모리", [
        ("node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes", "사용"),
        ("node_memory_MemTotal_bytes", "전체"),
    ], "bytes"), 8, 8)
    l.add(timeseries("디스크 남은 비율", [(
        'node_filesystem_avail_bytes{fstype!~"tmpfs|overlay|squashfs|ramfs|autofs"} / node_filesystem_size_bytes{fstype!~"tmpfs|overlay|squashfs|ramfs|autofs"}',
        "{{mountpoint}}")], "percentunit", thresholds=[(0.15, "red")]), 8, 8)
    service = variable("service", 'label_values(container_cpu_usage_seconds_total{service!=""}, service)', "컨테이너",
                       multi=True, include_all=True)
    return dashboard("errdeck-containers", "컨테이너 자원", l, [service], "13.5 컨테이너 자원. 14장의 상한이 맞는가")


def stores():
    """저장소가 버티는가."""
    l = Layout()
    l.row("PostgreSQL")
    l.add(timeseries("연결(상태별)", [
        ('sum by (state) (pg_stat_activity_count{datname="errdeck"})', "{{state}}"),
        ("pg_settings_max_connections", "최대"),
    ], "short", "합계 16 + 여유. 최대 30(14.6)"), 8, 8)
    l.add(timeseries("잠금", [('sum by (mode) (pg_locks_count{datname="errdeck"})', "{{mode}}")], "short", stack=True), 8, 8)
    l.add(timeseries("트랜잭션", [
        ('rate(pg_stat_database_xact_commit{datname="errdeck"}[$__rate_interval])', "커밋"),
        ('rate(pg_stat_database_xact_rollback{datname="errdeck"}[$__rate_interval])', "롤백"),
        ('rate(pg_stat_database_deadlocks{datname="errdeck"}[$__rate_interval])', "교착"),
    ], "ops"), 8, 8)

    l.row("Redis")
    l.add(timeseries("메모리", [
        ("redis_memory_used_bytes", "사용"),
        ("redis_memory_max_bytes", "상한"),
    ], "bytes", "상한에 닿으면 쓰기를 거부하고 ingest 가 503 을 응답한다(adr/0002)"), 8, 8)
    l.add(timeseries("명령", [("sum by (cmd) (rate(redis_commands_total[$__rate_interval]))", "{{cmd}}")], "ops"), 8, 8)
    l.add(timeseries("연결", [("redis_connected_clients", "연결"), ("redis_blocked_clients", "블로킹 읽기")], "short"), 8, 8)

    l.row("ClickHouse")
    l.add(timeseries("활성 파트 수", [("ClickHouseAsyncMetrics_MaxPartCountForPartition", "파티션당 최대")], "short",
                     "작게 여러 번 쓰면 늘어난다. 300 을 넘으면 쓰기가 늦어진다(10.4)", thresholds=[(150, "orange"), (300, "red")]), 8, 8)
    l.add(timeseries("쿼리", [
        ("rate(ClickHouseProfileEvents_SelectQuery[$__rate_interval])", "SELECT"),
        ("rate(ClickHouseProfileEvents_InsertQuery[$__rate_interval])", "INSERT"),
        ("rate(ClickHouseProfileEvents_FailedQuery[$__rate_interval])", "실패"),
    ], "ops"), 8, 8)
    l.add(timeseries("쿼리 시간(평균)", [(
        "rate(ClickHouseProfileEvents_QueryTimeMicroseconds[$__rate_interval]) / clamp_min(rate(ClickHouseProfileEvents_Query[$__rate_interval]), 1e-9) / 1e6",
        "평균")], "s"), 8, 8)
    l.add(timeseries("메모리", [("ClickHouseMetrics_MemoryTracking", "사용")], "bytes"), 8, 8)
    l.add(timeseries("병합", [("ClickHouseMetrics_Merge", "진행 중")], "short"), 8, 8)
    l.add(timeseries("넣은 행", [("rate(ClickHouseProfileEvents_InsertedRows[$__rate_interval])", "행")], "ops"), 8, 8)
    return dashboard("errdeck-stores", "ErrDeck 저장소", l, description="13.5 저장소. 저장소가 버티는가")


def main():
    OUT.mkdir(exist_ok=True)
    for name, build in [("overview", overview), ("pipeline", pipeline), ("jvm", jvm), ("containers", containers), ("stores", stores)]:
        (OUT / f"{name}.json").write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"dashboards/{name}.json")


if __name__ == "__main__":
    main()
