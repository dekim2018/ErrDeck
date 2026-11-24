<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from './components/AppShell.vue'

const route = useRoute()
const router = useRouter()
const bare = computed(() => route.meta.public === true)
// 새로고침하면 첫 이동에서 쿠키로 로그인을 되살린다. 그 전에 틀을 그리면 빈 사이드바가 잠깐 보이므로 기다린다.
const ready = ref(false)
router.isReady().then(() => {
  ready.value = true
})
</script>

<template>
  <template v-if="ready">
    <RouterView v-if="bare" />
    <AppShell v-else>
      <RouterView />
    </AppShell>
  </template>
</template>
