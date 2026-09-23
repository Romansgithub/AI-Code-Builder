/**
 * 品牌配置。所有品牌相关信息集中在此，更名只需改本文件。
 */
export const BRAND = {
  name: '妙笔生花',
  slug: 'miaobi',
  fullName: '妙笔生花 · AI 应用生成平台',
  slogan: '一句话，生成你的应用',
  subSlogan: '不写一行代码，把想法落笔成可上线的产品',
  description: '面向个人与团队的 AI 应用生成平台，输入一句话描述，即可生成、预览、部署完整的网站应用。',
  copyrightOwner: '妙笔生花团队',
  year: new Date().getFullYear(),
} as const

export default BRAND
