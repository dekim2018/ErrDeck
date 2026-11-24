<script setup lang="ts">
import { useSession } from '../stores/session'

// 전체 보기(시스템 카드, 요약 지표)는 22 작업에서 만든다. 지금은 시작 안내만 둔다.
const session = useSession()
</script>

<template>
  <section class="page">
    <h1>시작하기</h1>
    <p v-if="session.systems.length">왼쪽에서 시스템을 펼쳐 프로젝트를 고르면 이슈 목록이 보인다.</p>
    <p v-else>아직 속한 시스템이 없다. 관리자가 시스템을 만들고 구성원으로 넣어야 한다.</p>
  </section>
</template>
