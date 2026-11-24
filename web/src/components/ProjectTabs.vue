<script setup lang="ts">
// 프로젝트 화면의 탭. 이슈, 릴리스, 성능, 모니터, 알림.
defineProps<{ systemId: number; projectId: number }>()
</script>

<template>
  <nav class="tabs">
    <RouterLink :to="{ name: 'issues', params: { systemId, projectId } }">이슈</RouterLink>
    <RouterLink :to="{ name: 'releases', params: { systemId, projectId } }">릴리스</RouterLink>
    <RouterLink :to="{ name: 'performance', params: { systemId, projectId } }">성능</RouterLink>
    <RouterLink :to="{ name: 'monitors', params: { systemId, projectId } }">모니터</RouterLink>
    <RouterLink :to="{ name: 'alerts', params: { systemId, projectId } }">알림</RouterLink>
  </nav>
</template>
