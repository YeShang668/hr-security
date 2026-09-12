import { createPinia } from 'pinia'

// 单独导出 pinia 实例：路由守卫、Axios 拦截器都要用 store，
// 从 main.js 之外的地方导入该实例可以避免循环依赖
export default createPinia()
