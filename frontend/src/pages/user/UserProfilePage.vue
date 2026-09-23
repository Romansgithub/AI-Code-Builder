<template>
  <div id="userProfilePage">
    <div class="page-header">
      <div>
        <h1>个人中心</h1>
        <p class="page-subtitle">查看账号信息，完善你的昵称、头像与个人简介</p>
      </div>
    </div>

    <div class="profile-container">
      <a-card title="账号信息" :loading="loading">
        <a-descriptions :column="2" bordered>
          <a-descriptions-item label="账号">
            {{ loginUserStore.loginUser.userAccount }}
          </a-descriptions-item>
          <a-descriptions-item label="用户角色">
            <a-tag v-if="isAdmin" color="green">管理员</a-tag>
            <a-tag v-else color="blue">普通用户</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="昵称">
            {{ loginUserStore.loginUser.userName || '未填写' }}
          </a-descriptions-item>
          <a-descriptions-item label="注册时间">
            {{ formatTime(loginUserStore.loginUser.createTime) }}
          </a-descriptions-item>
        </a-descriptions>
      </a-card>

      <a-card title="编辑资料" style="margin-top: 24px">
        <a-form :model="formData" :rules="rules" layout="vertical" @finish="handleSubmit">
          <a-form-item label="昵称" name="userName">
            <a-input
              v-model:value="formData.userName"
              placeholder="请输入昵称"
              :maxlength="20"
              show-count
            />
          </a-form-item>

          <a-form-item
            label="头像"
            name="userAvatar"
            extra="支持图片链接，建议使用正方形图片"
          >
            <a-input v-model:value="formData.userAvatar" placeholder="请输入头像图片链接" />
            <div v-if="formData.userAvatar" class="avatar-preview">
              <a-avatar :src="formData.userAvatar" :size="64">
                {{ formData.userName?.charAt(0) || 'U' }}
              </a-avatar>
            </div>
          </a-form-item>

          <a-form-item label="个人简介" name="userProfile">
            <a-textarea
              v-model:value="formData.userProfile"
              placeholder="介绍一下自己吧"
              :rows="4"
              :maxlength="200"
              show-count
            />
          </a-form-item>

          <a-form-item>
            <a-space>
              <a-button type="primary" html-type="submit" :loading="submitting">
                保存修改
              </a-button>
              <a-button @click="resetForm">重置</a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </a-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { updateUserSelf } from '@/api/userController'
import { formatTime } from '@/utils/time'
import type { FormInstance } from 'ant-design-vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const loading = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')

const formData = reactive<API.UserSelfUpdateRequest>({
  userName: '',
  userAvatar: '',
  userProfile: '',
})

const rules = {
  userName: [
    { min: 1, max: 20, message: '昵称长度在 1-20 个字符', trigger: 'blur' },
  ],
  userAvatar: [{ type: 'url', message: '请输入有效的图片链接', trigger: 'blur' }],
  userProfile: [{ max: 200, message: '简介不超过 200 个字符', trigger: 'blur' }],
}

const loadData = async () => {
  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }
  loading.value = true
  try {
    await loginUserStore.fetchLoginUser()
    formData.userName = loginUserStore.loginUser.userName || ''
    formData.userAvatar = loginUserStore.loginUser.userAvatar || ''
    formData.userProfile = loginUserStore.loginUser.userProfile || ''
    clearValidate()
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  formData.userName = loginUserStore.loginUser.userName || ''
  formData.userAvatar = loginUserStore.loginUser.userAvatar || ''
  formData.userProfile = loginUserStore.loginUser.userProfile || ''
  clearValidate()
}

const clearValidate = () => {
  formRef.value?.clearValidate()
}

const handleSubmit = async () => {
  submitting.value = true
  try {
    const res = await updateUserSelf({
      userName: formData.userName,
      userAvatar: formData.userAvatar,
      userProfile: formData.userProfile,
    })
    if (res.data.code === 0) {
      message.success('保存成功')
      await loginUserStore.fetchLoginUser()
    } else {
      message.error('保存失败：' + res.data.message)
    }
  } catch (error) {
    console.error('保存个人信息失败：', error)
    message.error('保存失败，请重试')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
#userProfilePage {
  padding: 24px;
  min-height: calc(100vh - var(--header-height));
  background: var(--color-bg-page);
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--color-heading);
}

.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.page-subtitle {
  margin: 6px 0 0;
  font-size: 14px;
  color: var(--color-text-tertiary);
}

.profile-container {
  max-width: 720px;
}

.profile-container :deep(.ant-card) {
  border-color: var(--color-border);
  box-shadow: var(--shadow-sm);
}

.profile-container :deep(.ant-card-head) {
  background: var(--color-bg-elevated);
  border-bottom: 1px solid var(--color-border);
}

.profile-container :deep(.ant-card-head-title) {
  font-weight: 600;
  color: var(--color-heading);
}

.profile-container :deep(.ant-descriptions-item-label) {
  background: var(--color-bg-subtle);
  color: var(--color-text-secondary);
}

.avatar-preview {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg-subtle);
  display: inline-block;
}
</style>
