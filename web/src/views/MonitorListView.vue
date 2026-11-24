<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { Incident, Monitor } from '../api/types'
import ProjectTabs from '../components/ProjectTabs.vue'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { dateTime, relative } from '../util/format'

// 업타임 모니터(9장). 상태, 가동률, 장애 기록, 일시 중지.
const props = defineProps<{ systemId: number; projectId: number }>()
const session = useSession()
const workspace = useWorkspace()
const project = computed(() => workspace.find(props.projectId))
const monitors = ref<Monitor[]>([])
const incidents = ref<Record<number, Incident[]>>({})
const error = ref('')
const form = ref({ name: '', url: '', intervalSeconds: 60, expectedBody: '' })

async function load() {
  error.value = ''
  try {
    monitors.value = await session.client.monitors(props.projectId)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '모니터를 불러오지 못했다'
  }
}

async function create() {
  try {
    await session.client.createMonitor(props.projectId, {
      name: form.value.name || null,
      url: form.value.url,
      intervalSeconds: form.value.intervalSeconds,
      expectedBody: form.value.expectedBody || null,
    })
    form.value = { name: '', url: '', intervalSeconds: 60, expectedBody: '' }
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '모니터를 만들지 못했다'
  }
}

async function pause(m: Monitor) {
  await session.client.pauseMonitor(m.id, m.pausedUntil == null)
  await load()
}

async function remove(m: Monitor) {
  await session.client.deleteMonitor(m.id)
  await load()
}

async function toggleIncidents(m: Monitor) {
  if (incidents.value[m.id]) {
    delete incidents.value[m.id]
    return
  }
  incidents.value[m.id] = await session.client.incidents(m.id)
}

function uptime(v: number | null): string {
  return v == null ? '–' : `${(v * 100).toFixed(2)}%`
}

function status(m: Monitor): string {
  if (m.pausedUntil) return '중지'
  return { up: '정상', down: '다운', unknown: '확인 전' }[m.status]
}

watch(() => props.projectId, load, { immediate: true })
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ project?.slug ?? '프로젝트' }} <span class="muted">모니터</span></h1>
    </header>
    <ProjectTabs :system-id="systemId" :project-id="projectId" />
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <table class="issues">
      <thead>
        <tr>
          <th>모니터</th>
          <th>상태</th>
          <th class="num">24시간</th>
          <th class="num">30일</th>
          <th>마지막 점검</th>
          <th>인증서</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <template v-for="m in monitors" :key="m.id">
          <tr>
            <td>
              <div>{{ m.name ?? m.url }}</div>
              <div class="muted small mono">{{ m.method }} {{ m.url }} · {{ m.intervalSeconds }}초마다</div>
            </td>
            <td>
              <span class="signal" :data-signal="m.pausedUntil ? 'OFF' : m.status === 'down' ? 'DOWN' : m.status === 'up' ? 'OK' : 'WARN'">
                {{ status(m) }}
              </span>
              <div v-if="m.openIncidentSince" class="muted small">{{ relative(m.openIncidentSince) }}부터</div>
            </td>
            <td class="num">{{ uptime(m.uptime24h) }}</td>
            <td class="num">{{ uptime(m.uptime30d) }}</td>
            <td>
              <span v-if="m.lastCheckedAt" :title="dateTime(m.lastCheckedAt)">{{ relative(m.lastCheckedAt) }}</span>
              <div v-if="m.lastError" class="error small">{{ m.lastError }}</div>
            </td>
            <td class="small">{{ m.certExpiresAt ? dateTime(m.certExpiresAt) : '–' }}</td>
            <td class="actions">
              <button type="button" @click="pause(m)">{{ m.pausedUntil ? '다시 켜기' : '중지' }}</button>
              <button type="button" @click="toggleIncidents(m)">장애 기록</button>
              <button type="button" @click="remove(m)">지우기</button>
            </td>
          </tr>
          <tr v-if="incidents[m.id]">
            <td colspan="7">
              <ul class="incidents">
                <li v-for="i in incidents[m.id]" :key="i.id">
                  {{ dateTime(i.startedAt) }} ~ {{ i.endedAt ? dateTime(i.endedAt) : '진행 중' }}
                  <span class="muted">{{ i.cause }}</span>
                  <a v-if="i.grafanaUrl" :href="i.grafanaUrl" target="_blank" rel="noopener">그라파나</a>
                </li>
                <li v-if="incidents[m.id]!.length === 0" class="muted">장애 기록이 없다</li>
              </ul>
            </td>
          </tr>
        </template>
        <tr v-if="monitors.length === 0">
          <td colspan="7" class="empty">모니터가 없다</td>
        </tr>
      </tbody>
    </table>

    <form class="filters" @submit.prevent="create">
      <input v-model="form.name" placeholder="이름(선택)" aria-label="이름" />
      <input v-model="form.url" placeholder="https://example.com/actuator/health" aria-label="주소" required size="40" />
      <select v-model.number="form.intervalSeconds" aria-label="주기">
        <option :value="30">30초</option>
        <option :value="60">1분</option>
        <option :value="300">5분</option>
      </select>
      <input v-model="form.expectedBody" placeholder="본문에 있어야 할 문자열(선택)" aria-label="본문 조건" />
      <button type="submit">모니터 추가</button>
    </form>
  </section>
</template>
