<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'

// 왼쪽은 시스템과 그 안의 프로젝트, 오른쪽은 화면 본문이다(CHOICES 12-1).
const session = useSession()
const workspace = useWorkspace()
const route = useRoute()
const router = useRouter()
const open = ref<Record<number, boolean>>({})

async function toggle(systemId: number) {
  open.value[systemId] = !open.value[systemId]
  if (open.value[systemId]) await workspace.load(systemId)
}

async function logout() {
  await session.logout()
  await router.push({ name: 'login' })
}

function activeProject(projectId: number): boolean {
  return Number(route.params.projectId) === projectId
}

onMounted(async () => {
  const current = Number(route.params.systemId)
  if (current) {
    open.value[current] = true
    await workspace.load(current)
  }
})

watch(
  () => route.params.systemId,
  async (id) => {
    if (id) {
      open.value[Number(id)] = true
      await workspace.load(Number(id))
    }
  },
)
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand">ErrDeck</RouterLink>
      <nav>
        <div v-for="system in session.systems" :key="system.id" class="system">
          <button class="system-name" :aria-expanded="!!open[system.id]" @click="toggle(system.id)">
            <span class="caret">{{ open[system.id] ? '▾' : '▸' }}</span>{{ system.name }}
            <span class="role">{{ system.role }}</span>
          </button>
          <ul v-if="open[system.id]">
            <li v-for="project in workspace.projects[system.id] ?? []" :key="project.id">
              <RouterLink
                :to="{ name: 'issues', params: { systemId: system.id, projectId: project.id } }"
                :class="{ active: activeProject(project.id) }"
              >
                <span class="platform" :data-platform="project.platform">{{ project.platform === 'VUE' ? 'V' : 'S' }}</span>
                {{ project.slug }}
              </RouterLink>
            </li>
            <li v-if="(workspace.projects[system.id] ?? []).length === 0" class="empty">프로젝트 없음</li>
          </ul>
        </div>
        <p v-if="session.systems.length === 0" class="empty">속한 시스템이 없다</p>
      </nav>
      <footer>
        <span class="who">{{ session.user?.email }}</span>
        <button class="link" @click="logout">로그아웃</button>
      </footer>
    </aside>
    <main class="content">
      <slot />
    </main>
  </div>
</template>
