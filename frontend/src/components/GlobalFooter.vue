<template>
  <a-layout-footer class="footer">
    <div class="footer-inner">
      <div class="footer-main">
        <div class="footer-brand">
          <BrandLogo :size="34" light />
          <p class="footer-desc">{{ BRAND.description }}</p>
        </div>

        <div class="footer-columns">
          <div v-for="group in footerGroups" :key="group.title" class="footer-column">
            <h4 class="footer-title">{{ group.title }}</h4>
            <ul class="footer-list">
              <li v-for="link in group.links" :key="link.label">
                <a class="footer-link" href="#" @click.prevent>{{ link.label }}</a>
              </li>
            </ul>
          </div>
        </div>
      </div>

      <div class="footer-bottom">
        <span class="footer-copyright"
          >© {{ BRAND.year }} {{ BRAND.copyrightOwner }} · 保留所有权利</span
        >
        <span class="footer-slogan">{{ BRAND.slogan }}</span>
      </div>
    </div>
  </a-layout-footer>
</template>

<script setup lang="ts">
import BrandLogo from '@/components/BrandLogo.vue'
import { BRAND } from '@/config/brand'

const footerGroups = [
  {
    title: '产品',
    links: [
      { label: 'AI 应用生成' },
      { label: '模板中心' },
      { label: '一键部署' },
      { label: '代码导出' },
    ],
  },
  {
    title: '资源',
    links: [{ label: '使用文档' }, { label: '更新日志' }, { label: '常见问题' }, { label: '最佳实践' }],
  },
  {
    title: '关于',
    links: [{ label: '关于我们' }, { label: '联系我们' }, { label: '加入我们' }],
  },
  {
    title: '法律',
    links: [{ label: '服务条款' }, { label: '隐私政策' }],
  },
]
</script>

<style scoped>
.footer {
  margin-top: 64px;
  padding: 48px 24px 24px;
  background: #0f172a;
  color: rgba(255, 255, 255, 0.72);
}

.footer-inner {
  max-width: var(--container-width);
  margin: 0 auto;
}

.footer-main {
  display: flex;
  justify-content: space-between;
  gap: 48px;
  padding-bottom: 32px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.footer-brand {
  max-width: 320px;
}

.footer-desc {
  margin: 16px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.6);
}

.footer-columns {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 32px;
  flex: 1;
  max-width: 640px;
}

.footer-title {
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
}

.footer-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.footer-list li {
  margin-bottom: 10px;
}

.footer-link {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.62);
  transition: color var(--transition-base);
}

.footer-link:hover {
  color: #ffffff;
}

.footer-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 20px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
}

.footer-slogan {
  color: rgba(255, 255, 255, 0.5);
}

@media (max-width: 900px) {
  .footer-main {
    flex-direction: column;
    gap: 32px;
  }

  .footer-columns {
    grid-template-columns: repeat(2, 1fr);
    max-width: none;
  }

  .footer-bottom {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
}
</style>
