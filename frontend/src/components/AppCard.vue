<template>
  <div class="app-card">
    <div class="app-preview">
      <img v-if="app.cover" :src="app.cover" :alt="app.appName" />
      <div v-else class="app-placeholder">
        <AppstoreOutlined />
      </div>
      <span v-if="featured" class="app-badge">精选</span>
      <div class="app-overlay">
        <a-space>
          <a-button type="primary" @click="handleViewChat">查看对话</a-button>
          <a-button v-if="app.deployKey" ghost @click="handleViewWork">查看作品</a-button>
        </a-space>
      </div>
    </div>
    <div class="app-info">
      <a-avatar :src="app.user?.userAvatar" :size="36" class="app-avatar">
        {{ app.user?.userName?.charAt(0) || 'U' }}
      </a-avatar>
      <div class="app-info-right">
        <h3 class="app-title">{{ app.appName || '未命名应用' }}</h3>
        <p class="app-author">
          {{ app.user?.userName || (featured ? '官方' : '未知用户') }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { AppstoreOutlined } from '@ant-design/icons-vue'

interface Props {
  app: API.AppVO
  featured?: boolean
}

interface Emits {
  (e: 'view-chat', appId: string | number | undefined): void
  (e: 'view-work', app: API.AppVO): void
}

const props = withDefaults(defineProps<Props>(), {
  featured: false,
})

const emit = defineEmits<Emits>()

const handleViewChat = () => {
  emit('view-chat', props.app.id)
}

const handleViewWork = () => {
  emit('view-work', props.app)
}
</script>

<style scoped>
.app-card {
  position: relative;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  transition:
    transform var(--transition-base),
    box-shadow var(--transition-base),
    border-color var(--transition-base);
}

.app-card:hover {
  transform: translateY(-3px);
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-md);
}

.app-preview {
  position: relative;
  height: 176px;
  background: linear-gradient(135deg, #eef4ff 0%, #e4ecfb 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.app-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.4s ease;
}

.app-card:hover .app-preview img {
  transform: scale(1.04);
}

.app-placeholder {
  font-size: 44px;
  color: #93b4f5;
}

.app-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 500;
  color: #fff;
  background: linear-gradient(135deg, #f59e0b, #d97706);
  border-radius: 999px;
  box-shadow: 0 2px 6px rgba(217, 119, 6, 0.3);
}

.app-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.55);
  opacity: 0;
  transition: opacity var(--transition-base);
}

.app-card:hover .app-overlay {
  opacity: 1;
}

.app-info {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}

.app-avatar {
  flex-shrink: 0;
  background: var(--color-primary-bg);
  color: var(--color-primary-text);
}

.app-info-right {
  flex: 1;
  min-width: 0;
}

.app-title {
  margin: 0 0 3px;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-heading);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-author {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
