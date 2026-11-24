<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{ start: number }>()
const count = ref(props.start)
const doubled = computed(() => count.value * 2)
const increment = () => {
  count.value += 1
  if (count.value > 10) throw new Error('너무 크다')
}
</script>

<template>
  <button @click="increment">{{ count }} / {{ doubled }}</button>
</template>
