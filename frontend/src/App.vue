<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import { themeConfig } from '@/config/theme'
</script>

<template>
  <a-config-provider :theme="themeConfig">
    <BasicLayout />
  </a-config-provider>
</template>
