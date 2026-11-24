<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ApiError } from '../api/client'
import type { Card, Overview, ProjectOverview, Signal } from '../api/types'
import { useSession } from '../stores/session'

// 통합 화면(12장). 시스템마다 상태 신호 3단계, 프로젝트마다 한 줄과 요약 카드 6개(12.2). 30초마다 다시 읽는다.
const session = useSession()
const overview = ref<Overview | null>(null)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined

async function load() {
  try {
    overview.value = await session.client.overview()
    error.value = ''
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '통합 화면을 불러오지 못했다'
  }
}

const signalText: Record<Signal, string> = { OK: '정상', WARN: '주의', DOWN: '장애' }

function percent(v: number | null): string {
  return v == null ? '–' : `${Math.round(v * 100)}%`
}

function warn(card: Card, key: keyof Card): boolean {
  const v = card[key]
  if (v == null) return false
  return { up: v === 0, cpu: v > 0.8, heap: v > 0.85, p95Ms: false, errorRate: v > 0.01, poolPending: v > 0 }[key]
}

/** 그라파나의 JVM 대시보드, 그 서비스의 최근 1시간(12.4 첫째 줄). */
function grafana(p: ProjectOverview): string | null {
  const base = overview.value?.grafanaUrl
  return base ? `${base.replace(/\/$/, '')}/d/errdeck-jvm?var-job=${encodeURIComponent(p.slug)}&from=now-1h&to=now` : null
}

onMounted(() => {
  void load()
  timer = setInterval(load, 30_000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>전체 보기</h1>
      <span v-if="overview && !overview.metricsAvailable" class="muted" title="요약 카드는 프로메테우스에서 읽는다">
        프로메테우스에 닿지 않아 요약 카드를 비워 두었다
      </span>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="overview && overview.systems.length === 0">아직 속한 시스템이 없다. 관리자가 시스템을 만들고 구성원으로 넣어야 한다.</p>

    <article v-for="s in overview?.systems ?? []" :key="s.id" class="system-card">
      <header>
        <span class="signal" :data-signal="s.signal">{{ signalText[s.signal] }}</span>
        <h2>{{ s.name }}</h2>
      </header>
      <table class="issues">
        <thead>
          <tr>
            <th>프로젝트</th>
            <th class="num">미해결</th>
            <th class="num">새 이슈(24h)</th>
            <th class="num">모니터</th>
            <th class="num">생존</th>
            <th class="num">CPU</th>
            <th class="num">힙</th>
            <th class="num">p95</th>
            <th class="num">5xx</th>
            <th class="num">풀 대기</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in s.projects" :key="p.id">
            <td>
              <span class="signal dot" :data-signal="p.signal" :title="p.reasons.join(', ') || '정상'" />
              <RouterLink :to="{ name: 'issues', params: { systemId: s.id, projectId: p.id } }">{{ p.slug }}</RouterLink>
              <span class="muted"> {{ p.platform }}</span>
              <div v-if="p.reasons.length" class="muted small">{{ p.reasons.join(' · ') }}</div>
            </td>
            <td class="num">{{ p.openIssues }}<span v-if="p.regressed" class="error"> (재발 {{ p.regressed }})</span></td>
            <td class="num">{{ p.newIssues24h }}</td>
            <td class="num">
              <RouterLink :to="{ name: 'monitors', params: { systemId: s.id, projectId: p.id } }">
                <span :class="{ error: p.monitorsDown > 0 }">{{ p.monitors - p.monitorsDown }}/{{ p.monitors }}</span>
              </RouterLink>
            </td>
            <template v-if="p.card">
              <td class="num" :class="{ error: warn(p.card, 'up') }">{{ p.card.up === 0 ? '없음' : '살아 있음' }}</td>
              <td class="num" :class="{ error: warn(p.card, 'cpu') }">{{ percent(p.card.cpu) }}</td>
              <td class="num" :class="{ error: warn(p.card, 'heap') }">{{ percent(p.card.heap) }}</td>
              <td class="num">{{ p.card.p95Ms == null ? '–' : `${Math.round(p.card.p95Ms)}ms` }}</td>
              <td class="num" :class="{ error: warn(p.card, 'errorRate') }">
                {{ p.card.errorRate == null ? '–' : `${(p.card.errorRate * 100).toFixed(1)}%` }}
              </td>
              <td class="num" :class="{ error: warn(p.card, 'poolPending') }">{{ p.card.poolPending ?? '–' }}</td>
            </template>
            <td v-else colspan="6" class="muted small">{{ p.platform === 'vue' ? '브라우저 프로젝트는 서버 지표가 없다' : '지표 없음' }}</td>
            <td><a v-if="grafana(p) && p.card" :href="grafana(p)!" target="_blank" rel="noopener">그라파나</a></td>
          </tr>
        </tbody>
      </table>
    </article>
  </section>
</template>
