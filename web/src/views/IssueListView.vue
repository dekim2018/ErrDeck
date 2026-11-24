<script setup lang="ts">
import ProjectTabs from '../components/ProjectTabs.vue'
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { IssueSummary, Period, Sort } from '../api/types'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { compact, relative, STATUS_LABELS } from '../util/format'

const props = defineProps<{ systemId: number; projectId: number }>()
const session = useSession()
const workspace = useWorkspace()

const status = ref('unresolved')
const period = ref<Period>('24h')
const sort = ref<Sort>('recent')
const release = ref('')
const page = ref(0)
const issues = ref<IssueSummary[]>([])
const loading = ref(false)
const error = ref('')

const project = computed(() => workspace.find(props.projectId))

async function load() {
  loading.value = true
  error.value = ''
  try {
    issues.value = await session.client.issues(props.projectId, {
      status: status.value,
      release: release.value.trim(),
      period: period.value,
      sort: sort.value,
      page: page.value,
    })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '이슈를 불러오지 못했다'
    issues.value = []
  } finally {
    loading.value = false
  }
}

watch([() => props.projectId, status, period, sort], () => {
  page.value = 0
  load()
}, { immediate: true })
watch(page, load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ project?.slug ?? '프로젝트' }} <span class="muted">이슈</span></h1>
    </header>
    <ProjectTabs :system-id="systemId" :project-id="projectId" />

    <form class="filters" @submit.prevent="load">
      <label>상태
        <select v-model="status">
          <option value="">모두</option>
          <option v-for="(label, key) in STATUS_LABELS" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label>기간
        <select v-model="period">
          <option value="1h">1시간</option>
          <option value="24h">24시간</option>
          <option value="7d">7일</option>
          <option value="14d">14일</option>
          <option value="30d">30일</option>
        </select>
      </label>
      <label>정렬
        <select v-model="sort">
          <option value="recent">최근 발생</option>
          <option value="frequent">횟수</option>
          <option value="users">영향 사용자</option>
        </select>
      </label>
      <label>릴리스 <input v-model="release" placeholder="예: 1.4.2" @keyup.enter="load" /></label>
    </form>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <table class="issues" :aria-busy="loading">
      <thead>
        <tr>
          <th>이슈</th>
          <th class="num">횟수</th>
          <th class="num">사용자</th>
          <th>최근</th>
          <th>처음</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="issue in issues" :key="issue.id">
          <td>
            <RouterLink :to="{ name: 'issue', params: { systemId, issueId: issue.id } }" class="title">{{ issue.title }}</RouterLink>
            <div class="meta">
              <span class="badge" :data-status="issue.status">{{ STATUS_LABELS[issue.status] }}</span>
              <span v-if="issue.culprit" class="culprit">{{ issue.culprit }}</span>
              <span v-if="issue.lastRelease" class="muted">{{ issue.lastRelease }}</span>
            </div>
          </td>
          <td class="num">{{ compact(issue.count) }}</td>
          <td class="num">{{ compact(issue.users) }}</td>
          <td class="time" :title="issue.lastSeen">{{ relative(issue.lastSeen) }}</td>
          <td class="time" :title="issue.firstSeen">{{ relative(issue.firstSeen) }}</td>
        </tr>
        <tr v-if="!loading && issues.length === 0">
          <td colspan="5" class="empty">조건에 맞는 이슈가 없다</td>
        </tr>
      </tbody>
    </table>
    <nav v-if="sort === 'recent'" class="pager">
      <button :disabled="page === 0" @click="page--">이전</button>
      <span>{{ page + 1 }}쪽</span>
      <button :disabled="issues.length < 25" @click="page++">다음</button>
    </nav>
  </section>
</template>
