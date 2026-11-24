<script setup lang="ts">
import { ref } from 'vue'
import type { ExceptionInfo } from '../api/types'
import { frameLabel, groupFrames } from '../util/stack'

// 예외 사슬. 바깥 예외가 먼저, 원인이 뒤에 온다. 이어진 라이브러리 프레임은 접어 둔다.
defineProps<{ exceptions: ExceptionInfo[] }>()
const expanded = ref<Record<string, boolean>>({})
</script>

<template>
  <div v-for="(ex, i) in exceptions" :key="i" class="exception">
    <p class="ex-head"><span v-if="i > 0" class="muted">원인: </span><strong>{{ ex.type }}</strong></p>
    <p v-if="ex.value" class="ex-value">{{ ex.value }}</p>
    <ol class="frames">
      <template v-for="(group, g) in groupFrames(ex.frames ?? [])" :key="g">
        <li v-if="group.kind === 'app'" class="frame app">{{ frameLabel(group.frame) }}</li>
        <li v-else class="frame library">
          <button class="link" @click="expanded[`${i}-${g}`] = !expanded[`${i}-${g}`]">
            라이브러리 프레임 {{ group.frames.length }}개 {{ expanded[`${i}-${g}`] ? '접기' : '펼치기' }}
          </button>
          <ol v-if="expanded[`${i}-${g}`]">
            <li v-for="(f, k) in group.frames" :key="k">{{ frameLabel(f) }}</li>
          </ol>
        </li>
      </template>
    </ol>
  </div>
</template>
