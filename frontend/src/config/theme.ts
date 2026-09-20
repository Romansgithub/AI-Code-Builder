/**
 * Ant Design Vue 主题配置（企业蓝稳重风）
 * 与 src/styles/tokens.css 中的设计变量保持一致。
 */
export const themeConfig = {
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorLink: '#2563eb',
    colorSuccess: '#16a34a',
    colorWarning: '#d97706',
    colorError: '#dc2626',

    colorText: '#1f2937',
    colorTextSecondary: '#4b5563',
    colorTextTertiary: '#6b7280',
    colorTextQuaternary: '#9aa4b2',
    colorTextHeading: '#0f172a',

    colorBorder: '#e5e9f0',
    colorBorderSecondary: '#eef2f7',
    colorBgLayout: '#f4f7fb',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',

    borderRadius: 8,
    fontSize: 14,
    controlHeight: 36,
    wireframe: false,
    fontFamily:
      "'Inter', 'HarmonyOS Sans SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif",
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
      bodyBg: '#f4f7fb',
      footerBg: 'transparent',
      headerHeight: 64,
      headerPadding: '0 24px',
    },
    Button: {
      primaryShadow: 'none',
      defaultShadow: 'none',
      fontWeight: 500,
      controlHeight: 36,
      borderRadius: 8,
      defaultBorderColor: '#d7dee9',
    },
    Menu: {
      itemColor: '#4b5563',
      itemHoverColor: '#2563eb',
      itemSelectedColor: '#2563eb',
      horizontalItemSelectedColor: '#2563eb',
      activeBarHeight: 2,
      itemBorderRadius: 8,
      colorSplit: 'transparent',
      itemBg: 'transparent',
    },
    Card: {
      borderRadiusLG: 12,
      headerBg: '#ffffff',
      paddingLG: 24,
    },
    Table: {
      headerBg: '#f8fafc',
      headerColor: '#4b5563',
      headerSplitColor: 'transparent',
      rowHoverBg: '#f8fafc',
      borderColor: '#e5e9f0',
      cellPaddingBlock: 14,
    },
    Input: {
      borderRadius: 8,
      controlHeight: 36,
    },
    Select: {
      borderRadius: 8,
      controlHeight: 36,
    },
    Modal: {
      borderRadiusLG: 14,
    },
    Tag: {
      borderRadiusSM: 6,
    },
    Pagination: {
      borderRadius: 8,
    },
  },
}

export default themeConfig
