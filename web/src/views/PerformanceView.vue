<script setup lang="ts">
import ProjectTabs from '../components/ProjectTabs.vue'
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { EndpointTable, TransactionView } from '../api/types'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { dateTime, relative } from '../util/format'

// 성능 화면(12.3). 위는 엔드포인트 표(프로메테우스, 전체 요청), 줄을 누르면 그 엔드포인트의 느린 요청 목록(표본 트랜잭션).
const props = defineProps<{ systemId: number; projectId: number }>()
const session = useSession()
const workspace = useWorkspace()
const project = computed(() => workspace.find(props.projectId))
const period = ref('24h')
const table = ref<EndpointTable | null>(null)
const selected = ref<string | null>(null)
const slow = ref<TransactionView[]>([])
const error = ref('')

async function load() {
  error.value = ''
  try {
    table.value = await session.client.endpoints(props.projectId, period.value)
    await loadSlow()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '성능 정보를 불러오지 못했다'
  }
}

async function loadSlow() {
  slow.value = await session.client.slowTransactions(props.projectId, selected.value, period.value)
}

function pick(name: string) {
  selected.value = selected.value === name ? null : name
  void loadSlow()
}

function repeated(tx: TransactionView): number {
  return Math.max(0, ...tx.spans.map((s) => s.repeat))
}

watch(() => [props.projectId, period.value], load, { immediate: true })
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ project?.slug ?? '프로젝트' }} <span class="muted">성능</span></h1>
      <select v-model="period" aria-label="기간">
        <option value="1h">1시간</option>
        <option value="24h">24시간</option>
        <option value="7d">7일</option>
      </select>
    </header>
    <ProjectTabs :system-id="systemId" :project-id="projectId" />
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <p v-if="table?.source === 'SAMPLES'" class="muted">
      프로메테우스에서 이 프로젝트의 요청 지표를 읽지 못해 표본 트랜잭션으로 계산했다. 요청 수와 p95 는 실제와 다르다(8.1).
    </p>
    <table class="issues">
      <thead>
        <tr>
          <th>엔드포인트</th>
          <th class="num">요청</th>
          <th class="num">p95</th>
          <th class="num">에러율</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="e in table?.endpoints ?? []" :key="e.name" class="clickable" :class="{ selected: e.name === selected }" @click="pick(e.name)">
          <td class="mono">{{ e.name }}</td>
          <td class="num">{{ e.requests.toLocaleString() }}</td>
          <td class="num">{{ Math.round(e.p95Ms).toLocaleString() }}ms</td>
          <td class="num"><span :class="{ error: e.errorRate >= 0.01 }">{{ (e.errorRate * 100).toFixed(1) }}%</span></td>
        </tr>
        <tr v-if="table && table.endpoints.length === 0">
          <td colspan="4" class="empty">이 기간에 기록된 요청이 없다</td>
        </tr>
      </tbody>
    </table>

    <h2>느린 요청 <span class="muted">{{ selected ?? '전체' }}</span></h2>
    <table class="issues">
      <thead>
        <tr>
          <th>트랜잭션</th>
          <th class="num">시간</th>
          <th>상태</th>
          <th>언제</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="tx in slow" :key="tx.spanId">
          <td>
            <RouterLink :to="{ name: 'trace', params: { systemId, traceId: tx.traceId } }" class="mono">{{ tx.name }}</RouterLink>
            <span v-if="repeated(tx) >= 10" class="badge warn" title="같은 쿼리가 연속으로 반복되었다(8.5)">반복 쿼리 ×{{ repeated(tx) }}</span>
            <span v-if="tx.partial" class="badge" title="표본으로 뽑히지 않았지만 에러나 느림으로 남긴 것. 앞뒤 서비스의 구간이 없다">부분</span>
          </td>
          <td class="num">{{ tx.durationMs.toLocaleString() }}ms</td>
          <td><span :class="{ error: tx.status === 'error' || tx.status.startsWith('5') }">{{ tx.status }}</span></td>
          <td class="time" :title="dateTime(tx.timestamp)">{{ relative(tx.timestamp) }}</td>
        </tr>
        <tr v-if="slow.length === 0">
          <td colspan="4" class="empty">기록된 트랜잭션이 없다. SDK 의 errdeck.tracing 설정을 확인한다</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
