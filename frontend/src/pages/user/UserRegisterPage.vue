<template>
  <div id="userRegisterPage" class="auth-page">
    <div class="auth-card">
      <aside class="auth-brand">
        <BrandLogo :size="38" light />
        <div class="auth-brand-body">
          <h2 class="auth-brand-title">{{ BRAND.slogan }}</h2>
          <p class="auth-brand-desc">{{ BRAND.description }}</p>
          <ul class="auth-brand-list">
            <li>免费注册，即刻开始创作</li>
            <li>AI 对话式生成网站应用</li>
            <li>作品可导出代码与一键部署</li>
          </ul>
        </div>
        <p class="auth-brand-foot">© {{ BRAND.year }} {{ BRAND.copyrightOwner }}</p>
      </aside>

      <div class="auth-form">
        <h1 class="auth-title">创建账号</h1>
        <p class="auth-subtitle">注册后即可开始生成你的第一个应用</p>

        <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
          <a-form-item
            name="userName"
            :rules="[
              { required: true, message: '请输入昵称' },
              { min: 1, max: 20, message: '昵称长度不能超过 20 个字符' },
            ]"
          >
            <a-input
              v-model:value="formState.userName"
              size="large"
              placeholder="请输入昵称（将展示在头像旁）"
              :maxlength="20"
            />
          </a-form-item>
          <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
            <a-input
              v-model:value="formState.userAccount"
              size="large"
              placeholder="请输入账号"
            />
          </a-form-item>
          <a-form-item
            name="userPassword"
            :rules="[
              { required: true, message: '请输入密码' },
              { min: 8, message: '密码不能小于 8 位' },
            ]"
          >
            <a-input-password
              v-model:value="formState.userPassword"
              size="large"
              placeholder="请输入密码"
            />
          </a-form-item>
          <a-form-item
            name="checkPassword"
            :rules="[
              { required: true, message: '请确认密码' },
              { min: 8, message: '密码不能小于 8 位' },
              { validator: validateCheckPassword },
            ]"
          >
            <a-input-password
              v-model:value="formState.checkPassword"
              size="large"
              placeholder="请确认密码"
            />
          </a-form-item>
          <div class="auth-tips">
            已有账号？
            <RouterLink to="/user/login">去登录</RouterLink>
          </div>
          <a-form-item class="auth-submit-item">
            <a-button type="primary" size="large" html-type="submit" block>注册</a-button>
          </a-form-item>
        </a-form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { userRegister } from '@/api/userController.ts'
import { message } from 'ant-design-vue'
import { reactive } from 'vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { BRAND } from '@/config/brand'

const router = useRouter()

const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userName: '',
  userPassword: '',
  checkPassword: '',
})

const validateCheckPassword = (rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value && value !== formState.userPassword) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

const handleSubmit = async (values: API.UserRegisterRequest) => {
  const res = await userRegister(values)
  if (res.data.code === 0) {
    message.success('注册成功')
    router.push({
      path: '/user/login',
      replace: true,
    })
  } else {
    message.error('注册失败，' + res.data.message)
  }
}
</script>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px 24px;
  min-height: calc(100vh - var(--header-height));
}

.auth-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  width: 100%;
  max-width: 920px;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.auth-brand {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px;
  color: #fff;
  background:
    radial-gradient(600px 240px at 10% 0%, rgba(96, 165, 250, 0.45) 0%, transparent 60%),
    linear-gradient(160deg, #1e40af 0%, #1d4ed8 55%, #2563eb 100%);
}

.auth-brand-body {
  margin: 32px 0;
}

.auth-brand-title {
  margin: 0 0 12px;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.5px;
}

.auth-brand-desc {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.78);
}

.auth-brand-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.auth-brand-list li {
  position: relative;
  padding-left: 20px;
  margin-bottom: 10px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.9);
}

.auth-brand-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #93c5fd;
}

.auth-brand-foot {
  margin: 0;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
}

.auth-form {
  padding: 48px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.auth-title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: var(--color-heading);
}

.auth-subtitle {
  margin: 0 0 28px;
  font-size: 14px;
  color: var(--color-text-tertiary);
}

.auth-tips {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.auth-submit-item {
  margin-bottom: 0;
}

@media (max-width: 768px) {
  .auth-card {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    padding: 28px;
  }

  .auth-brand-body {
    margin: 16px 0;
  }

  .auth-form {
    padding: 32px 24px;
  }
}
</style>
