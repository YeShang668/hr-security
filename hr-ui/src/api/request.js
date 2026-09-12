import axios from 'axios'
import { ElMessage } from 'element-plus'

/** token 与用户信息的本地存储 key（刷新页面不丢登录态） */
export const TOKEN_KEY = 'hr_token'
export const USER_KEY = 'hr_user'

const service = axios.create({
  // 相对路径：开发环境由 Vite dev proxy 转发到 8080，生产由 Nginx 同源转发
  baseURL: '/api',
  timeout: 10000
})

/**
 * 401 统一处理：清登录态 + 回登录页。
 * 用动态 import 拿 store/router，避免 request → router → store → api → request 的循环依赖。
 * 注意（BUG5-1）：必须清 Pinia 里的登录态，只删 localStorage 不够——
 * store 是内存态且只在应用启动时读一次 localStorage，漏清会出现
 * "token 已失效但 isLogin 仍为 true"，路由守卫把用户弹回首页而不是登录页。
 */
let redirecting = false
async function handleUnauthorized(message) {
  if (redirecting) return
  redirecting = true
  ElMessage.error(message)
  try {
    const [{ default: pinia }, { useUserStore }, { default: router }] = await Promise.all([
      import('@/stores'),
      import('@/stores/user'),
      import('@/router')
    ])
    useUserStore(pinia).clearAuth()
    const current = router.currentRoute.value
    if (current.path !== '/login') {
      await router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
  } finally {
    // 稍后再解除标记，避免并发请求弹出多个提示
    setTimeout(() => (redirecting = false), 1000)
  }
}

// 请求拦截器：自动带 token，前端不用每次手写 Authorization
service.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 响应拦截器：统一拆 Result {code, message, data}。
 * 关键坑（第 2 周就踩过）：业务错误 400/403/409/500 的 HTTP 状态码仍是 200，
 * 只有 Security 层的未登录才是真 HTTP 401，所以两处都要判。
 */
service.interceptors.response.use(
  (response) => {
    const res = response.data
    // 非统一结构（理论上不会出现）直接放行，避免把正常数据吞掉
    if (res === null || typeof res !== 'object' || !('code' in res)) {
      return response.data
    }
    if (res.code === 200) {
      return res
    }
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      // token 过期、已登出、被管理员禁用/踢下线
      handleUnauthorized('登录状态已失效，请重新登录')
    } else if (status === 403) {
      ElMessage.error('无权限访问该资源')
    } else if (status >= 500) {
      ElMessage.error('服务异常，请稍后重试')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查后端服务是否启动')
    } else {
      ElMessage.error(error.response?.data?.message || '网络异常，请检查后端服务')
    }
    return Promise.reject(error)
  }
)

export default service
