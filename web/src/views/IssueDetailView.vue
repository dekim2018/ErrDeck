<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { IssueDetail, IssueStatus, Period } from '../api/types'
import StackTrace from '../components/StackTrace.vue'
import TrendBars from '../components/TrendBars.vue'
import { useSession } from '../stores/session'
import { compact, dateTime, relative, STATUS_LABELS } from '../util/format'

const props = defineProps<{ systemId: number; issueId: number }>()
const session = useSession()
const detail = ref<IssueDetail | null>(null)
const period = ref<Period>('24h')
const error = ref('')
const comment = ref('')
const busy = ref(false)

// 상태 변경과 댓글은 MEMBER 이상(15.4). 버튼은 역할이 모자라면 감춘다. 서버가 다시 확인한다.
const canEdit = computed(() => {
  const role = session.roleIn(props.systemId)
  return role === 'MEMBER' || role === 'ADMIN' || role === 'OWNER'
})

async function load() {
  error.value = ''
  try {
    detail.value = await session.client.issue(props.issueId, period.value)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '이슈를 불러오지 못했다'
  }
}

async function setStatus(status: IssueStatus) {
  busy.value = true
  try {
    const extra = status === 'resolved' && detail.value?.issue.lastRelease ? { resolvedInRelease: detail.value.issue.lastRelease } : {}
    await session.client.changeStatus(props.issueId, status, extra)
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '상태를 바꾸지 못했다'
  } finally {
    busy.value = false
  }
}

async function addComment() {
  if (!comment.value.trim()) return
  busy.value = true
  try {
    await session.client.comment(props.issueId, comment.value.trim())
    comment.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '댓글을 남기지 못했다'
  } finally {
    busy.value = false
  }
}

watch([() => props.issueId, period], load, { immediate: true })
</script>

<template>
  <section v-if="detail" class="page detail">
    <header class="page-head">
      <div>
        <span class="badge" :data-status="detail.issue.status">{{ STATUS_LABELS[detail.issue.status] }}</span>
        <h1>{{ detail.issue.title }}</h1>
        <p v-if="detail.issue.culprit" class="culprit">{{ detail.issue.culprit }}</p>
      </div>
      <div v-if="canEdit" class="actions">
        <button v-if="detail.issue.status !== 'resolved'" class="primary" :disabled="busy" @click="setStatus('resolved')">해결</button>
        <button v-if="detail.issue.status !== 'ignored'" :disabled="busy" @click="setStatus('ignored')">무시</button>
        <button v-if="detail.issue.status !== 'unresolved'" :disabled="busy" @click="setStatus('unresolved')">미해결로</button>
      </div>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <dl class="facts">
      <div><dt>횟수</dt><dd>{{ compact(detail.issue.count) }}</dd></div>
      <div><dt>영향 사용자</dt><dd>{{ compact(detail.issue.users) }}</dd></div>
      <div><dt>처음</dt><dd :title="dateTime(detail.issue.firstSeen)">{{ relative(detail.issue.firstSeen) }}</dd></div>
      <div><dt>최근</dt><dd :title="dateTime(detail.issue.lastSeen)">{{ relative(detail.issue.lastSeen) }}</dd></div>
      <div v-if="detail.issue.lastRelease"><dt>최근 릴리스</dt><dd>{{ detail.issue.lastRelease }}</dd></div>
      <div v-if="detail.resolvedInRelease"><dt>해결한 릴리스</dt><dd>{{ detail.resolvedInRelease }}</dd></div>
    </dl>

    <section class="panel">
      <header class="panel-head">
        <h2>발생 추이</h2>
        <select v-model="period" aria-label="기간">
          <option value="1h">1시간</option>
          <option value="24h">24시간</option>
          <option value="7d">7일</option>
          <option value="14d">14일</option>
          <option value="30d">30일</option>
        </select>
        <a :href="detail.grafanaUrl" target="_blank" rel="noopener" class="link">처음 발생 무렵의 서버 지표(그라파나)</a>
      </header>
      <TrendBars :buckets="detail.trend" />
    </section>

    <section v-if="detail.latestEvent" class="panel">
      <h2>최근 이벤트 <span class="muted">{{ detail.latestEventAt ? dateTime(detail.latestEventAt) : '' }} {{ detail.latestEnvironment }}</span></h2>
      <StackTrace :exceptions="detail.latestEvent.exceptions ?? []" />
      <p v-if="!detail.latestEvent.exceptions?.length && detail.latestEvent.message">{{ detail.latestEvent.message }}</p>

      <template v-if="detail.latestEvent.request">
        <h3>요청</h3>
        <p class="mono">{{ detail.latestEvent.request.method }} {{ detail.latestEvent.request.url }}<span v-if="detail.latestEvent.request.query_string">?{{ detail.latestEvent.request.query_string }}</span></p>
        <table class="kv">
          <tr v-for="(value, name) in detail.latestEvent.request.headers ?? {}" :key="name"><th>{{ name }}</th><td>{{ value }}</td></tr>
        </table>
      </template>

      <template v-if="detail.latestEvent.tags && Object.keys(detail.latestEvent.tags).length">
        <h3>태그</h3>
        <ul class="tags"><li v-for="(value, name) in detail.latestEvent.tags" :key="name">{{ name }}={{ value }}</li></ul>
      </template>

      <template v-if="detail.latestEvent.breadcrumbs?.length">
        <h3>빵부스러기</h3>
        <ol class="crumbs">
          <li v-for="(crumb, i) in detail.latestEvent.breadcrumbs" :key="i">
            <span class="muted">{{ dateTime(crumb.timestamp) }}</span> <strong>{{ crumb.category }}</strong> {{ crumb.message }}
          </li>
        </ol>
      </template>
      <p v-if="detail.traceId" class="muted">
        이 에러가 난 요청의 흐름:
        <RouterLink :to="{ name: 'trace', params: { systemId, traceId: detail.traceId } }" class="mono">{{ detail.traceId }}</RouterLink>
      </p>
    </section>
    <section v-else class="panel"><p class="muted">30일 안의 원본이 없다. 건수만 남아 있다.</p></section>

    <section class="panel">
      <h2>댓글</h2>
      <ul class="comments">
        <li v-for="c in detail.comments" :key="c.id"><strong>{{ c.author }}</strong> <span class="muted">{{ relative(c.createdAt) }}</span><p>{{ c.body }}</p></li>
        <li v-if="detail.comments.length === 0" class="muted">아직 댓글이 없다</li>
      </ul>
      <form v-if="canEdit" class="comment-form" @submit.prevent="addComment">
        <textarea v-model="comment" rows="3" placeholder="원인이나 조치를 남긴다" />
        <button class="primary" type="submit" :disabled="busy || !comment.trim()">남기기</button>
      </form>
    </section>
  </section>
  <section v-else class="page"><p v-if="error" class="error" role="alert">{{ error }}</p><p v-else class="muted">불러오는 중</p></section>
</template>
