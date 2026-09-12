import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/layout/MainLayout.vue'

/**
 * 路由表即"菜单表"：meta.title/icon 驱动侧边栏渲染，meta.roles 控制可访问角色。
 * 与后端 @PreAuthorize 的口径保持一致：
 *   /users     → 仅 ADMIN（后端 UserController 全部 hasRole('ADMIN')）
 *   /employees → ADMIN 可增删改、EMPLOYEE 只读（页面内用 isAdmin 控制按钮显隐）
 *   /depts     → 同上
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '首页', icon: 'HomeFilled' }
      },
      {
        path: 'employees',
        name: 'employees',
        component: () => import('@/views/EmployeeManageView.vue'),
        meta: { title: '员工管理', icon: 'User', roles: ['ADMIN', 'EMPLOYEE'] }
      },
      {
        path: 'depts',
        name: 'depts',
        component: () => import('@/views/DeptManageView.vue'),
        meta: { title: '部门管理', icon: 'OfficeBuilding', roles: ['ADMIN', 'EMPLOYEE'] }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UserManageView.vue'),
        meta: { title: '用户管理', icon: 'Setting', roles: ['ADMIN'] }
      }
    ]
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权限' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫：
 * 1. 未登录访问受保护页面 → 跳登录，并记住原目标（登录后跳回）；
 * 2. 有 token 但刷新后内存里没有用户信息 → 调 /me 恢复（顺带校验 token 是否已被服务端踢下线）；
 * 3. 角色不匹配 → 跳 403 页（后端仍会独立拦截，这里只是别让用户看到空页面）。
 */
router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    // 已登录再访问登录页：直接进首页
    return userStore.isLogin ? { path: '/' } : true
  }

  if (!userStore.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!userStore.userInfo) {
    try {
      await userStore.fetchMe()
    } catch (e) {
      userStore.clearAuth()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const allowed = to.meta.roles
  if (allowed && !allowed.some((role) => userStore.hasRole(role))) {
    return { path: '/403' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} - 人事敏感数据安全管控系统`
    : '人事敏感数据安全管控系统'
})

export default router
