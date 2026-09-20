# 妙笔生花 · AI 应用生成平台（前端）

> 一句话，生成你的应用。

妙笔生花是一个面向个人与团队的 AI 应用生成平台。用户只需输入一段自然语言描述，即可由 AI 生成、实时预览、在线编辑并一键部署完整的网站应用。

## 功能特性

### 用户功能

- **应用创建**：输入一句提示词即可创建应用
- **AI 对话生成**：通过与 AI 多轮对话持续完善应用，实时流式输出
- **实时预览**：右侧实时预览生成结果，支持元素级可视化编辑
- **应用管理**：查看、修改、删除自己的应用
- **一键部署 / 代码导出**：部署到云端或下载完整源码
- **精选案例**：浏览平台精选的优质应用

### 管理员功能

- **应用管理**：查询、编辑、删除任意应用，设置精选
- **用户管理**：查询与维护平台用户
- **对话管理**：检索与维护对话记录

## 页面结构

- `/` 首页：Hero 与创建入口、我的作品、精选案例
- `/app/chat/:id` 生成工作台：对话区 + 实时预览区
- `/app/edit/:id` 应用信息编辑
- `/admin/appManage` 应用管理
- `/admin/userManage` 用户管理
- `/admin/chatManage` 对话管理
- `/user/login`、`/user/register` 登录 / 注册

## 技术栈

- **框架**：Vue 3 + TypeScript
- **UI**：Ant Design Vue
- **路由**：Vue Router 4
- **状态管理**：Pinia
- **构建工具**：Vite
- **HTTP**：Axios（SSE 实时流）
- **规范**：ESLint + Prettier

## 设计系统

- 品牌与文案集中配置于 `src/config/brand.ts`
- 主题 Token 集中配置于 `src/config/theme.ts`（Ant Design Vue ConfigProvider）
- 设计变量（颜色 / 圆角 / 阴影 / 字体）定义于 `src/styles/tokens.css`

## 开发环境

```sh
npm install
npm run dev
```

默认代理 `/api` 到 `http://localhost:8080`。

### 构建与检查

```sh
npm run build      # 类型检查 + 构建
npm run type-check # 仅类型检查
npm run lint       # ESLint 自动修复
npm run format     # Prettier 格式化
```

## 目录结构

```
src/
├── api/          # 接口定义与类型
├── components/   # 公共组件（BrandLogo、AiAvatar、AppCard、PageHeader 等）
├── config/       # 品牌、主题、环境配置
├── layouts/      # 布局组件
├── pages/        # 页面（home / app / admin / user）
├── router/       # 路由配置
├── stores/       # Pinia 状态
├── styles/       # 设计变量与全局样式
└── utils/        # 工具函数
```

## 环境变量

在 `.env.development` / `.env.production` 中配置：

```
VITE_DEPLOY_DOMAIN=http://localhost   # 应用部署域名
VITE_API_BASE_URL=/api                 # 后端 API 基础地址
```

## 许可证

本项目采用 MIT 许可证。
