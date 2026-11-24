<script setup lang="ts">
import ProjectTabs from '../components/ProjectTabs.vue'
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { ReleaseSummary } from '../api/types'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { dateTime, relative } from '../util/format'

// 릴리스별 새 이슈와 재발, 소스맵 업로드 상태(12.3).
const props = defineProps<{ systemId: number; projectId: number }>()
const session = useSession()
const workspace = useWorkspace()
const releases = ref<ReleaseSummary[]>([])
const error = ref('')
const project = computed(() => workspace.find(props.projectId))

async function load() {
  error.value = ''
  try {
    releases.value = await session.client.releases(props.projectId)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '릴리스를 불러오지 못했다'
  }
}

watch(() => props.projectId, load, { immediate: true })
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ project?.slug ?? '프로젝트' }} <span class="muted">릴리스</span></h1>
    </header>
    <ProjectTabs :system-id="systemId" :project-id="projectId" />
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <table class="issues">
      <thead>
        <tr>
          <th>릴리스</th>
          <th>등록</th>
          <th class="num">소스맵</th>
          <th class="num">새 이슈</th>
          <th class="num">재발</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in releases" :key="r.version">
          <td class="mono">{{ r.version }}</td>
          <td class="time" :title="dateTime(r.createdAt)">{{ relative(r.createdAt) }}</td>
          <td class="num">
            <span v-if="r.artifacts">{{ r.artifacts }}개</span>
            <span v-else class="muted" title="이 릴리스의 브라우저 에러는 원본으로 되돌릴 수 없다">없음</span>
          </td>
          <td class="num">{{ r.newIssues }}</td>
          <td class="num"><span :class="{ error: r.regressed > 0 }">{{ r.regressed }}</span></td>
        </tr>
        <tr v-if="releases.length === 0">
          <td colspan="5" class="empty">등록된 릴리스가 없다. 빌드 뒤 scripts/upload-sourcemaps.sh 로 올린다</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
