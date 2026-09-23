<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ThunderboltOutlined, ArrowRightOutlined } from '@ant-design/icons-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { addApp, listMyAppVoByPage, listGoodAppVoByPage } from '@/api/appController'
import { getDeployUrl } from '@/config/env'
import { BRAND } from '@/config/brand'
import AppCard from '@/components/AppCard.vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const userPrompt = ref('')
const creating = ref(false)

const templates = [
  {
    label: '个人博客',
    prompt:
      '创建一个现代化的个人博客网站，包含文章列表、详情页、分类标签、搜索功能、评论系统和个人简介页面。采用简洁的设计风格，支持响应式布局，文章支持 Markdown 格式，首页展示最新文章和热门推荐。',
  },
  {
    label: '企业官网',
    prompt:
      '设计一个专业的企业官网，包含公司介绍、产品服务展示、新闻资讯、联系我们等页面。采用商务风格的设计，包含轮播图、产品展示卡片、团队介绍、客户案例展示，支持多语言切换和在线客服功能。',
  },
  {
    label: '在线商城',
    prompt:
      '构建一个功能完整的在线商城，包含商品展示、购物车、用户注册登录、订单管理、支付结算等功能。设计现代化的商品卡片布局，支持商品搜索筛选、用户评价、优惠券系统和会员积分功能。',
  },
  {
    label: '作品集网站',
    prompt:
      '制作一个精美的作品展示网站，适合设计师、摄影师、艺术家等创作者。包含作品画廊、项目详情页、个人简历、联系方式等模块。采用网格布局展示作品，支持图片放大预览和作品分类筛选。',
  },
]

const myApps = ref<API.AppVO[]>([])
const myAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

const featuredApps = ref<API.AppVO[]>([])
const featuredAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

const setPrompt = (prompt: string) => {
  userPrompt.value = prompt
}

const createApp = async () => {
  if (!userPrompt.value.trim()) {
    message.warning('请输入应用描述')
    return
  }

  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }

  creating.value = true
  try {
    const res = await addApp({
      initPrompt: userPrompt.value.trim(),
    })

    if (res.data.code === 0 && res.data.data) {
      message.success('应用创建成功')
      const appId = String(res.data.data)
      await router.push(`/app/chat/${appId}`)
    } else {
      message.error('创建失败：' + res.data.message)
    }
  } catch (error) {
    console.error('创建应用失败：', error)
    message.error('创建失败，请重试')
  } finally {
    creating.value = false
  }
}

const loadMyApps = async () => {
  if (!loginUserStore.loginUser.id) {
    return
  }

  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppsPage.current,
      pageSize: myAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records || []
      myAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载我的应用失败：', error)
  }
}

const loadFeaturedApps = async () => {
  try {
    const res = await listGoodAppVoByPage({
      pageNum: featuredAppsPage.current,
      pageSize: featuredAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records || []
      featuredAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载精选应用失败：', error)
  }
}

const viewChat = (appId: string | number | undefined) => {
  if (appId) {
    router.push(`/app/chat/${appId}?view=1`)
  }
}

const viewWork = (app: API.AppVO) => {
  if (app.deployKey) {
    const url = getDeployUrl(app.deployKey)
    window.open(url, '_blank')
  }
}

onMounted(() => {
  loadMyApps()
  loadFeaturedApps()
})
</script>

<template>
  <div id="homePage">
    <section id="create" class="hero">
      <div class="container hero-inner">
        <span class="hero-badge">
          <ThunderboltOutlined />
          {{ BRAND.fullName }}
        </span>
        <h1 class="hero-title">{{ BRAND.slogan }}</h1>
        <p class="hero-subtitle">{{ BRAND.subSlogan }}</p>

        <div class="composer">
          <a-textarea
            v-model:value="userPrompt"
            placeholder="描述你想创建的应用，例如：帮我创建一个现代化的个人博客网站"
            :rows="4"
            :maxlength="1000"
            :bordered="false"
            class="composer-input"
          />
          <div class="composer-footer">
            <div class="composer-meta">
              <span class="composer-count">{{ userPrompt.length }}/1000</span>
            </div>
            <a-button
              type="primary"
              size="large"
              :loading="creating"
              class="composer-submit"
              @click="createApp"
            >
              <template #icon>
                <ThunderboltOutlined />
              </template>
              生成应用
            </a-button>
          </div>
        </div>

        <div class="templates">
          <span class="templates-label">快速开始：</span>
          <button
            v-for="item in templates"
            :key="item.label"
            type="button"
            class="template-chip"
            @click="setPrompt(item.prompt)"
          >
            {{ item.label }}
          </button>
        </div>
      </div>
    </section>

    <div class="container home-body">
      <section id="my-apps" class="section">
        <div class="section-header">
          <div>
            <h2 class="section-title">我的作品</h2>
            <p class="section-subtitle">在这里管理你创建的全部应用</p>
          </div>
        </div>
        <div v-if="myApps.length" class="app-grid">
          <AppCard
            v-for="app in myApps"
            :key="app.id"
            :app="app"
            @view-chat="viewChat"
            @view-work="viewWork"
          />
        </div>
        <div v-else class="empty-block">
          <a-empty description="还没有作品，输入一句话开始创作吧" />
        </div>
        <div v-if="myAppsPage.total > myAppsPage.pageSize" class="pagination-wrapper">
          <a-pagination
            v-model:current="myAppsPage.current"
            v-model:page-size="myAppsPage.pageSize"
            :total="myAppsPage.total"
            :show-size-changer="false"
            :show-total="(total: number) => `共 ${total} 个应用`"
            @change="loadMyApps"
          />
        </div>
      </section>

      <section id="featured" class="section">
        <div class="section-header">
          <div>
            <h2 class="section-title">精选案例</h2>
            <p class="section-subtitle">看看其他人用一句话创造了什么</p>
          </div>
          <a-button type="link" class="section-more">
            浏览更多
            <ArrowRightOutlined />
          </a-button>
        </div>
        <div v-if="featuredApps.length" class="app-grid">
          <AppCard
            v-for="app in featuredApps"
            :key="app.id"
            :app="app"
            :featured="true"
            @view-chat="viewChat"
            @view-work="viewWork"
          />
        </div>
        <div v-else class="empty-block">
          <a-empty description="暂无精选案例" />
        </div>
        <div v-if="featuredAppsPage.total > featuredAppsPage.pageSize" class="pagination-wrapper">
          <a-pagination
            v-model:current="featuredAppsPage.current"
            v-model:page-size="featuredAppsPage.pageSize"
            :total="featuredAppsPage.total"
            :show-size-changer="false"
            :show-total="(total: number) => `共 ${total} 个案例`"
            @change="loadFeaturedApps"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
#homePage {
  width: 100%;
  min-height: calc(100vh - var(--header-height));
  background:
    radial-gradient(1200px 500px at 50% -160px, rgba(37, 99, 235, 0.1) 0%, transparent 70%),
    linear-gradient(180deg, #ffffff 0%, var(--color-bg-page) 42%);
}

.hero {
  position: relative;
  padding: 72px 0 48px;
  border-bottom: 1px solid var(--color-border);
}

.hero-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-primary-text);
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border);
  border-radius: 999px;
}

.hero-title {
  margin: 20px 0 12px;
  font-size: 44px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -1px;
  color: var(--color-heading);
}

.hero-subtitle {
  margin: 0 0 36px;
  font-size: 16px;
  color: var(--color-text-tertiary);
}

.composer {
  width: 100%;
  max-width: 760px;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-md);
  overflow: hidden;
  transition:
    border-color var(--transition-base),
    box-shadow var(--transition-base);
}

.composer:focus-within {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12), var(--shadow-md);
}

.composer-input {
  font-size: 15px;
  line-height: 1.7;
  padding: 18px 20px 4px;
  resize: none;
}

.composer-input :deep(textarea) {
  font-size: 15px;
  line-height: 1.7;
}

.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px 16px 20px;
}

.composer-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.composer-count {
  font-size: 12px;
  color: var(--color-text-quaternary);
}

.composer-submit {
  height: 42px;
  padding: 0 24px;
  font-weight: 600;
}

.templates {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 22px;
}

.templates-label {
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.template-chip {
  padding: 7px 16px;
  font-size: 13px;
  font-family: inherit;
  color: var(--color-text-secondary);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  cursor: pointer;
  transition: all var(--transition-base);
}

.template-chip:hover {
  color: var(--color-primary);
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.home-body {
  padding-top: 56px;
  padding-bottom: 8px;
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 24px;
}

.empty-block {
  padding: 40px 0;
  background: var(--color-bg-elevated);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-lg);
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

.section-more {
  padding: 0;
}

@media (max-width: 768px) {
  .hero {
    padding: 48px 0 32px;
  }

  .hero-title {
    font-size: 30px;
  }

  .hero-subtitle {
    font-size: 14px;
  }

  .composer-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .composer-meta {
    justify-content: space-between;
  }

  .composer-submit {
    width: 100%;
  }

  .app-grid {
    grid-template-columns: 1fr;
  }
}
</style>
