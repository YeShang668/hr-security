import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, getMe } from '@/api/auth'
import { TOKEN_KEY, USER_KEY } from '@/api/request'

/**
 * 登录态：token + 用户信息（含角色）。
 * 持久化到 localStorage，刷新页面后由路由守卫调 /me 重新校验（token 可能已被服务端踢掉）。
 * 前端角色只用于"显示/隐藏菜单"，真正的权限判断在后端 @PreAuthorize——前端隐藏不等于安全。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  }),

  getters: {
    roles: (state) => state.userInfo?.roles || [],
    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '',
    isLogin: (state) => !!state.token,
    isAdmin: (state) => (state.userInfo?.roles || []).includes('ADMIN')
  },

  actions: {
    /** 登录：存 token + 用户信息 */
    async login(form) {
      const res = await loginApi(form)
      this.setAuth(res.data.token, res.data.user)
      return res
    },

    /** 恢复/刷新登录态：以服务端 /me 返回的最新角色为准（角色变更即时生效） */
    async fetchMe() {
      const res = await getMe()
      this.setAuth(this.token, res.data)
      return res.data
    },

    setAuth(token, userInfo) {
      this.token = token
      this.userInfo = userInfo
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(userInfo))
    },

    /** 清本地登录态（不发请求），401 拦截和登出都用它 */
    clearAuth() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },

    /** 登出：先让服务端删会话（旧 token 立即失效），再清本地 */
    async logout() {
      try {
        await logoutApi()
      } catch (e) {
        // 会话已失效（401）等情况不影响本地清理
      } finally {
        this.clearAuth()
      }
    },

    /** 判断当前用户是否具备某个角色（菜单过滤用） */
    hasRole(role) {
      return this.roles.includes(role)
    }
  }
})
