<template>
  <div class="page-header">
    <div class="page-header-main">
      <h1 class="page-header-title">{{ title }}</h1>
      <p v-if="subtitle" class="page-header-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.extra" class="page-header-extra">
      <slot name="extra" />
    </div>
  </div>
</template>

<script setup lang="ts">
interface Props {
  title: string
  subtitle?: string
}

defineProps<Props>()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.page-header-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.3px;
  color: var(--color-heading);
}

.page-header-subtitle {
  margin: 6px 0 0;
  font-size: 14px;
  color: var(--color-text-tertiary);
}

.page-header-extra {
  flex-shrink: 0;
}
</style>
