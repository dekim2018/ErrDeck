<script setup lang="ts">
import { computed } from 'vue'
import type { Bucket } from '../api/types'
import { dateTime } from '../util/format'

// 발생 추이. 구간마다 막대 하나다. 차트 라이브러리 없이 SVG 로 그린다(CHOICES 12-3).
const props = defineProps<{ buckets: Bucket[] }>()
const max = computed(() => Math.max(1, ...props.buckets.map((b) => b.times)))
const width = 600
const height = 80
const barWidth = computed(() => (props.buckets.length ? width / props.buckets.length : width))
</script>

<template>
  <svg class="trend" :viewBox="`0 0 ${width} ${height}`" preserveAspectRatio="none" role="img" aria-label="발생 추이">
    <rect
      v-for="(b, i) in buckets"
      :key="b.start"
      :x="i * barWidth + 1"
      :y="height - (b.times / max) * height"
      :width="Math.max(1, barWidth - 2)"
      :height="(b.times / max) * height"
    >
      <title>{{ dateTime(b.start) }}, {{ b.times }}건</title>
    </rect>
  </svg>
  <p v-if="buckets.length === 0" class="muted">이 기간에 발생한 것이 없다</p>
</template>
