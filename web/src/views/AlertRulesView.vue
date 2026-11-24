<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { AlertRule, NotificationView } from '../api/types'
import ProjectTabs from '../components/ProjectTabs.vue'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { dateTime, relative } from '../util/format'

// 알림 규칙과 최근 알림(16장).
const props = defineProps<{ systemId: number; projectId: number }>()
const session = useSession()
const workspace = useWorkspace()
const project = computed(() => workspace.find(props.projectId))
const rules = ref<AlertRule[]>([])
const sent = ref<NotificationView[]>([])
const error = ref('')
const form = ref({ kind: 'new_issue', channel: 'discord', target: '', threshold: 100, windowMinutes: 5, cooldownMinutes: 30 })

const kinds: Record<string, string> = {
  new_issue: '새 이슈',
  regression: '재발',
  frequency: '빈도 초과',
  monitor_down: '다운과 복구',
  cert_expiry: '인증서 만료 임박',
  resource: '자원 경보(Alertmanager)',
}

async function load() {
  error.value = ''
  try {
    ;[rules.value, sent.value] = await Promise.all([
      session.client.alertRules(props.projectId),
      session.client.notifications(props.projectId),
    ])
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '알림 규칙을 불러오지 못했다'
  }
}

async function create() {
  const f = form.value
  try {
    await session.client.createAlertRule(props.projectId, {
      kind: f.kind,
      channel: f.channel,
      target: f.target,
      cooldownMinutes: f.cooldownMinutes,
      ...(f.kind === 'frequency' ? { threshold: f.threshold, windowMinutes: f.windowMinutes } : {}),
    })
    form.value.target = ''
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '규칙을 만들지 못했다'
  }
}

async function toggle(r: AlertRule) {
  await session.client.toggleAlertRule(r.id, !r.enabled)
  await load()
}

watch(() => props.projectId, load, { immediate: true })
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ project?.slug ?? '프로젝트' }} <span class="muted">알림</span></h1>
    </header>
    <ProjectTabs :system-id="systemId" :project-id="projectId" />
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <table class="issues">
      <thead>
        <tr>
          <th>규칙</th>
          <th>채널</th>
          <th class="num">재알림 간격</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rules" :key="r.id" :class="{ muted: !r.enabled }">
          <td>
            {{ kinds[r.kind] ?? r.kind }}
            <span v-if="r.kind === 'frequency'" class="muted">{{ r.windowMinutes }}분에 {{ r.threshold }}건 이상</span>
          </td>
          <td class="mono small">{{ r.channel }} {{ r.target }}</td>
          <td class="num">{{ r.cooldownMinutes }}분</td>
          <td class="actions"><button type="button" @click="toggle(r)">{{ r.enabled ? '끄기' : '켜기' }}</button></td>
        </tr>
        <tr v-if="rules.length === 0">
          <td colspan="4" class="empty">규칙이 없다</td>
        </tr>
      </tbody>
    </table>

    <form class="filters" @submit.prevent="create">
      <select v-model="form.kind" aria-label="규칙">
        <option v-for="(label, k) in kinds" :key="k" :value="k">{{ label }}</option>
      </select>
      <template v-if="form.kind === 'frequency'">
        <input v-model.number="form.windowMinutes" type="number" min="1" max="1440" aria-label="구간(분)" style="width: 5em" />분에
        <input v-model.number="form.threshold" type="number" min="1" aria-label="기준 건수" style="width: 6em" />건
      </template>
      <select v-model="form.channel" aria-label="채널">
        <option value="discord">Discord</option>
        <option value="slack">Slack</option>
      </select>
      <input v-model="form.target" placeholder="웹훅 주소" aria-label="웹훅 주소" required size="36" />
      <input v-model.number="form.cooldownMinutes" type="number" min="1" max="1440" aria-label="재알림 간격(분)" style="width: 5em" />분
      <button type="submit">규칙 추가</button>
    </form>

    <h2>최근 알림</h2>
    <table class="issues">
      <thead>
        <tr>
          <th>알림</th>
          <th>상태</th>
          <th>언제</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="n in sent" :key="n.id">
          <td>{{ n.title }} <span class="muted small">{{ kinds[n.kind] ?? n.kind }}</span></td>
          <td>
            <span :class="{ error: n.status === 'failed' }">{{ { pending: '보내는 중', sent: '보냄', failed: '실패' }[n.status] }}</span>
            <span v-if="n.attempts > 1" class="muted small"> {{ n.attempts }}번 시도</span>
            <div v-if="n.lastError" class="error small">{{ n.lastError }}</div>
          </td>
          <td class="time" :title="dateTime(n.createdAt)">{{ relative(n.createdAt) }}</td>
        </tr>
        <tr v-if="sent.length === 0">
          <td colspan="3" class="empty">보낸 알림이 없다</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
