<template>
  <a-layout-header class="header">
    <div class="header-inner">
      <!-- 品牌 -->
      <RouterLink to="/" class="header-brand">
        <BrandLogo :size="34" />
      </RouterLink>

      <!-- 导航 -->
      <nav class="header-nav">
        <a
          v-for="item in visibleNavItems"
          :key="item.key"
          class="nav-link"
          :class="{ 'nav-link--active': isActive(item) }"
          @click="handleNavClick(item)"
        >
          {{ item.label }}
        </a>
      </nav>

      <!-- 用户操作 -->
      <div class="header-actions">
        <template v-if="loginUserStore.loginUser.id">
          <a-button type="primary" @click="startCreate">开始创作</a-button>
          <a-dropdown placement="bottomRight">
            <div class="user-trigger">
              <a-avatar :src="loginUserStore.loginUser.userAvatar" :size="32">
                {{ loginUserStore.loginUser.userName?.charAt(0) || 'U' }}
              </a-avatar>
              <span class="user-name">{{ loginUserStore.loginUser.userName ?? '未命名' }}</span>
              <DownOutlined class="user-caret" />
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item @click="startCreate">
                  <AppstoreOutlined />
                  我的作品
                </a-menu-item>
                <a-menu-item @click="goToProfile">
                  <UserOutlined />
                  个人中心
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item @click="doLogout">
                  <LogoutOutlined />
                  退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <template v-else>
          <a-button type="text" class="login-link" @click="router.push('/user/login')">登录</a-button>
          <a-button type="primary" @click="router.push('/user/register')">免费开始</a-button>
        </template>
      </div>
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import {
  LogoutOutlined,
  AppstoreOutlined,
  DownOutlined,
  HomeOutlined,
  ThunderboltOutlined,
  StarOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'

interface NavItem {
  key: string
  label: string
  route?: string
  scrollTo?: string
  icon?: () => unknown
  admin?: boolean
}

const loginUserStore = useLoginUserStore()
const route = useRoute()
const router = useRouter()

const navItems: NavItem[] = [
  { key: '/', label: '首页', route: '/', icon: () => h(HomeOutlined) },
  { key: 'create', label: '开始创作', scrollTo: 'create', icon: () => h(ThunderboltOutlined) },
  { key: 'my-apps', label: '我的作品', scrollTo: 'my-apps', icon: () => h(AppstoreOutlined) },
  { key: 'featured', label: '精选案例', scrollTo: 'featured', icon: () => h(StarOutlined) },
  { key: '/admin/appManage', label: '应用管理', route: '/admin/appManage', admin: true },
  { key: '/admin/userManage', label: '用户管理', route: '/admin/userManage', admin: true },
  { key: '/admin/chatManage', label: '对话管理', route: '/admin/chatManage', admin: true },
]

const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')

const visibleNavItems = computed(() =>
  navItems.filter((item) => !item.admin || isAdmin.value),
)

const isActive = (item: NavItem) => {
  if (item.route) {
    return item.route === '/' ? route.path === '/' : route.path.startsWith(item.route)
  }
  return false
}

const handleNavClick = async (item: NavItem) => {
  if (item.route) {
    if (route.path !== item.route) {
      await router.push(item.route)
    }
    return
  }
  if (item.scrollTo) {
    if (route.path !== '/') {
      await router.push('/')
      await new Promise((resolve) => setTimeout(resolve, 120))
    }
    document.getElementById(item.scrollTo)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

const goToProfile = async () => {
  if (route.path !== '/user/profile') {
    await router.push('/user/profile')
  }
}

const startCreate = async () => {
  if (route.path !== '/') {
    await router.push('/')
    await new Promise((resolve) => setTimeout(resolve, 120))
  }
  document.getElementById('create')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({ userName: '未登录' })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: var(--header-height);
  line-height: normal;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--color-border);
  padding: 0;
}

.header-inner {
  max-width: var(--container-width);
  height: 100%;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 32px;
}

.header-brand {
  display: flex;
  align-items: center;
  text-decoration: none;
  flex-shrink: 0;
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.nav-link {
  position: relative;
  padding: 8px 14px;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-secondary);
  border-radius: var(--radius-md);
  cursor: pointer;
  white-space: nowrap;
  transition:
    color var(--transition-base),
    background-color var(--transition-base);
}

.nav-link:hover {
  color: var(--color-primary);
  background: var(--color-bg-subtle);
}

.nav-link--active {
  color: var(--color-primary);
}

.nav-link--active::after {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--color-primary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.login-link {
  color: var(--color-text-secondary);
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition-base);
}

.user-trigger:hover {
  background: var(--color-bg-subtle);
}

.user-name {
  font-size: 14px;
  color: var(--color-text);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-caret {
  font-size: 10px;
  color: var(--color-text-quaternary);
}

@media (max-width: 900px) {
  .header-inner {
    gap: 16px;
  }

  .header-nav {
    display: none;
  }

  .header-actions {
    margin-left: auto;
  }

  .user-name {
    display: none;
  }
}
</style>
