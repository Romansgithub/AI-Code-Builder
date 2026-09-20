<template>
  <svg
    class="ai-avatar"
    :width="size"
    :height="size"
    viewBox="0 0 48 48"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    role="img"
    aria-label="AI 助手"
  >
    <defs>
      <linearGradient :id="gradientId" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#3b82f6" />
        <stop offset="100%" stop-color="#1d4ed8" />
      </linearGradient>
    </defs>
    <circle cx="24" cy="24" r="24" :fill="`url(#${gradientId})`" />
    <rect x="12.5" y="15" width="23" height="18" rx="6.5" fill="#ffffff" />
    <circle cx="19.5" cy="24" r="2.7" fill="#1d4ed8" />
    <circle cx="28.5" cy="24" r="2.7" fill="#1d4ed8" />
    <rect x="22.6" y="8.6" width="2.8" height="6.4" rx="1.4" fill="#ffffff" />
    <circle cx="24" cy="7.6" r="2.4" fill="#ffffff" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  size?: number
}

withDefaults(defineProps<Props>(), {
  size: 32,
})

const gradientId = computed(() => `ai-grad-${Math.random().toString(36).slice(2, 9)}`)
</script>

<style scoped>
.ai-avatar {
  display: block;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
