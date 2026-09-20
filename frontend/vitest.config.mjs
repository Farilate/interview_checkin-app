import { defineConfig } from 'vitest/config'

// 纯 JS 测试独立配置，不加载 UniApp 插件、不改变 H5 编译配置。
export default defineConfig({ test: { environment: 'node', include: ['tests/**/*.test.js'], unstubGlobals: true } })
