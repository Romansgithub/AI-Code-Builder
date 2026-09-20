/**
 * 品牌配置
 *
 * 所有品牌相关信息集中在此处，如需更名只需修改本文件。
 * 品牌名「妙笔生花」取典：五代·王仁裕《开元天宝遗事·梦笔头生花》。
 */
export const BRAND = {
  // 品牌主名（四字）
  name: '妙笔生花',
  // 拼音标识（用于 Logo、页脚等）
  slug: 'miaobi',
  // 完整名称
  fullName: '妙笔生花 · AI 应用生成平台',
  // 主标语
  slogan: '一句话，生成你的应用',
  // 次标语
  subSlogan: '不写一行代码，把想法落笔成可上线的产品',
  // 产品一句话介绍
  description: '面向个人与团队的 AI 应用生成平台，输入一句话描述，即可生成、预览、部署完整的网站应用。',
  // 版权归属
  copyrightOwner: '妙笔生花团队',
  // 年份（自动获取）
  year: new Date().getFullYear(),
} as const

export default BRAND
