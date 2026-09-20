<template>
  <span
    class="brand-logo"
    :class="{ 'brand-logo--stacked': stacked, 'brand-logo--light': light }"
  >
    <svg
      class="brand-mark"
      :width="size"
      :height="size"
      viewBox="0 0 40 40"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label="妙笔生花"
    >
      <defs>
        <linearGradient :id="gradientId" x1="0" y1="0" x2="40" y2="40" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#3b82f6" />
          <stop offset="100%" stop-color="#1d4ed8" />
        </linearGradient>
      </defs>
      <rect width="40" height="40" rx="11" :fill="`url(#${gradientId})`" />
      <g fill="#ffffff">
        <ellipse cx="20" cy="12.4" rx="3" ry="5.2" />
        <ellipse cx="20" cy="27.6" rx="3" ry="5.2" />
        <ellipse cx="12.4" cy="20" rx="5.2" ry="3" />
        <ellipse cx="27.6" cy="20" rx="5.2" ry="3" />
      </g>
      <circle cx="20" cy="20" r="3.4" fill="#ffffff" />
      <circle cx="20" cy="20" r="1.7" fill="#2563eb" />
    </svg>
    <span v-if="showText" class="brand-text">
      <span class="brand-name">{{ BRAND.name }}</span>
      <span v-if="stacked" class="brand-slogan">{{ BRAND.slogan }}</span>
    </span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BRAND } from '@/config/brand'

interface Props {
  size?: number
  showText?: boolean
  stacked?: boolean
  light?: boolean
}

withDefaults(defineProps<Props>(), {
  size: 32,
  showText: true,
  stacked: false,
  light: false,
})

const gradientId = computed(
  () => `brand-grad-${Math.random().toString(36).slice(2, 9)}`,
)
</script>

<style scoped>
.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
}

.brand-mark {
  display: block;
  flex-shrink: 0;
  border-radius: 11px;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.24);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-name {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--color-heading);
}

.brand-slogan {
  font-size: 12px;
  font-weight: 400;
  color: var(--color-text-tertiary);
  letter-spacing: 0.5px;
}

.brand-logo--light .brand-name {
  color: #ffffff;
}

.brand-logo--light .brand-slogan {
  color: rgba(255, 255, 255, 0.6);
}
</style>
