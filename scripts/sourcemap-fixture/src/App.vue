<script setup lang="ts">
import { ref } from 'vue'
import Counter from './components/Counter.vue'
import { applyRemote } from './collab'

const message = ref('소스맵 테스트')
function broken() {
  applyRemote({ ops: ['a', 'b'] })
  throw new Error('버튼에서 난 에러')
}
</script>

<template>
  <h1>{{ message }}</h1>
  <Counter :start="3" />
  <button @click="broken">에러 내기</button>
</template>
