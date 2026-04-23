<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  src?: string | null
  alt?: string
  imgClass?: string
  placeholderClass?: string
  placeholderText?: string
  maxRetries?: number
}>(), {
  alt: '',
  imgClass: 'w-full h-full object-cover',
  placeholderClass: 'flex items-center justify-center text-4xl opacity-20',
  placeholderText: '🎬',
  maxRetries: 2,
})

const loaded = ref(false)
const failedAfterRetries = ref(false)
const attempt = ref(0)
let retryTimer: ReturnType<typeof setTimeout> | null = null

const baseSrc = computed(() => {
  if (!props.src) return null
  return props.src.startsWith('//') ? 'https:' + props.src : props.src
})

const effectiveSrc = computed(() => {
  if (!baseSrc.value) return null
  if (attempt.value === 0) return baseSrc.value
  const sep = baseSrc.value.includes('?') ? '&' : '?'
  return `${baseSrc.value}${sep}__retry=${attempt.value}`
})

function clearRetryTimer() {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
}

watch(baseSrc, () => {
  loaded.value = false
  failedAfterRetries.value = false
  attempt.value = 0
  clearRetryTimer()
}, { immediate: true })

function onLoad() {
  loaded.value = true
  failedAfterRetries.value = false
  clearRetryTimer()
}

function onError() {
  if (attempt.value >= props.maxRetries) {
    failedAfterRetries.value = true
    return
  }
  const delayMs = 3000 + attempt.value * 5000
  clearRetryTimer()
  retryTimer = setTimeout(() => {
    attempt.value += 1
  }, delayMs)
}

onBeforeUnmount(clearRetryTimer)
</script>

<template>
  <div class="relative w-full h-full">
    <div
      :class="['absolute inset-0 pointer-events-none', placeholderClass]"
      aria-hidden="true"
    >{{ placeholderText }}</div>
    <img
      v-if="effectiveSrc && !failedAfterRetries"
      :key="effectiveSrc"
      :src="effectiveSrc"
      :alt="alt"
      :class="[imgClass, 'relative transition-opacity duration-500', loaded ? 'opacity-100' : 'opacity-0']"
      loading="lazy"
      decoding="async"
      referrerpolicy="no-referrer"
      @load="onLoad"
      @error="onError"
    />
  </div>
</template>
