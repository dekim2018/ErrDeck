<script setup lang="ts">
import { ref } from 'vue'
import { captureException } from '@errdeck/sdk-vue'
import { applyRemote } from './collab'

// 에러 경로마다 버튼 하나. 컴포넌트 처리기, 처리되지 않은 Promise 거절, window 의 에러, 직접 보내기.
const last = ref('')
const release = __RELEASE__

function componentError() {
  last.value = '컴포넌트 에러'
  applyRemote({})
}

function unhandledRejection() {
  last.value = '처리되지 않은 거절'
  void Promise.reject(new Error('저장 요청이 거절되었다'))
}

function windowError() {
  last.value = '타이머 안의 에러'
  setTimeout(() => {
    throw new RangeError('페이지 번호가 범위를 벗어났다')
  })
}

function manual() {
  last.value = '직접 보내기'
  try {
    JSON.parse('{깨진 JSON')
  } catch (e) {
    captureException(e, { feature: 'import' })
  }
}
</script>

<template>
  <main>
    <h1>ErrDeck 데모 화면</h1>
    <p>버튼을 누르면 에러가 나고 Vue SDK 가 ingest 로 보낸다. 릴리스 {{ release }}</p>
    <button id="component-error" @click="componentError">컴포넌트 에러</button>
    <button id="unhandled-rejection" @click="unhandledRejection">처리되지 않은 거절</button>
    <button id="window-error" @click="windowError">타이머 안의 에러</button>
    <button id="manual" @click="manual">직접 보내기</button>
    <p v-if="last">방금: {{ last }}</p>
  </main>
</template>
