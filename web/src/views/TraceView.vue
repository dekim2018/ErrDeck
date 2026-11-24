<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { TransactionView } from '../api/types'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { dateTime } from '../util/format'

// 요청 하나의 흐름(8.2). 서비스마다 트랜잭션 한 줄, 그 아래에 스팬. 가로 막대의 위치와 길이가 시간이다.
const props = defineProps<{ systemId: number; traceId: string }>()
const session = useSession()
const workspace = useWorkspace()
const txs = ref<TransactionView[]>([])
const error = ref('')

interface Row {
  key: string
  depth: number
  label: string
  detail: string
  start: number
  duration: number
  kind: 'tx' | 'span'
  repeat: number
  error: boolean
}

const origin = computed(() => Math.min(...txs.value.map((t) => Date.parse(t.timestamp))))
const total = computed(() => Math.max(1, ...txs.value.map((t) => Date.parse(t.timestamp) - origin.value + t.durationMs)))

/** 부모 span_id 를 따라 트랜잭션을 나무로 놓는다. 부모를 못 찾으면(부분 추적, 보이지 않는 프로젝트) 맨 위에 둔다. */
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const byParent = new Map<string | null, TransactionView[]>()
  const spanOwner = new Map<string, TransactionView>()
  for (const tx of txs.value) for (const s of tx.spans) spanOwner.set(s.spanId, tx)
  const ids = new Set(txs.value.map((t) => t.spanId))
  for (const tx of txs.value) {
    const parent = tx.parentSpanId && (ids.has(tx.parentSpanId) || spanOwner.has(tx.parentSpanId)) ? tx.parentSpanId : null
    byParent.set(parent, [...(byParent.get(parent) ?? []), tx])
  }
  const visit = (tx: TransactionView, depth: number) => {
    const start = Date.parse(tx.timestamp) - origin.value
    const project = workspace.find(tx.projectId)?.slug ?? `프로젝트 ${tx.projectId}`
    out.push({ key: tx.spanId, depth, label: tx.name, detail: project, start, duration: tx.durationMs, kind: 'tx', repeat: 1,
      error: tx.status === 'error' || tx.status.startsWith('5') })
    for (const child of byParent.get(tx.spanId) ?? []) visit(child, depth + 1)
    for (const s of tx.spans) {
      out.push({ key: s.spanId, depth: depth + 1, label: s.description || s.op, detail: s.op, start: start + s.startOffsetMs,
        duration: s.durationMs, kind: 'span', repeat: s.repeat, error: false })
      for (const child of byParent.get(s.spanId) ?? []) visit(child, depth + 2)
    }
  }
  for (const root of byParent.get(null) ?? []) visit(root, 0)
  return out
})

async function load() {
  error.value = ''
  try {
    txs.value = await session.client.trace(props.traceId)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '추적을 불러오지 못했다'
  }
}

watch(() => props.traceId, load, { immediate: true })
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>추적 <span class="muted mono">{{ traceId }}</span></h1>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="txs.length" class="muted">
      {{ dateTime(txs[0]!.timestamp) }} 시작, 전체 {{ total.toLocaleString() }}ms, 서비스 {{ new Set(txs.map((t) => t.projectId)).size }}개
    </p>
    <div class="waterfall">
      <div v-for="r in rows" :key="r.key" class="wf-row" :class="r.kind">
        <div class="wf-label" :style="{ paddingLeft: `${r.depth * 14}px` }" :title="r.label">
          <span :class="{ error: r.error }">{{ r.label }}</span>
          <span class="muted"> {{ r.detail }}</span>
          <span v-if="r.repeat >= 10" class="badge warn" title="같은 내용이 연속으로 반복되어 하나로 접었다(8.5)">반복 ×{{ r.repeat }}</span>
          <span v-else-if="r.repeat > 1" class="muted"> ×{{ r.repeat }}</span>
        </div>
        <div class="wf-track">
          <div class="wf-bar" :class="{ error: r.error }"
            :style="{ left: `${(r.start / total) * 100}%`, width: `${Math.max(0.3, (r.duration / total) * 100)}%` }" />
          <span class="wf-time">{{ r.duration.toLocaleString() }}ms</span>
        </div>
      </div>
    </div>
  </section>
</template>
