<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon><Lock /></el-icon>
        <div class="logo-text">
          <div class="logo-title">人事敏感数据</div>
          <div class="logo-sub">安全管控系统</div>
        </div>
      </div>

      <el-menu
        :default-active="activeMenu"
        router
        background-color="#1f2d3d"
        text-color="#c0c4cc"
        active-text-color="#ffd04b"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>

      <div class="aside-footer">
        开发演示环境<br />数据为测试数据
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="page-title">{{ route.meta.title || '' }}</span>
          <el-tag v-if="!userStore.isAdmin" type="warning" size="small" effect="plain">
            只读账号
          </el-tag>
        </div>

        <div class="header-right">
          <el-tag
            v-for="role in userStore.roles"
            :key="role"
            :type="role === 'ADMIN' ? 'danger' : 'info'"
            size="small"
            effect="dark"
          >
            {{ role }}
          </el-tag>
          <el-dropdown @command="onCommand">
            <span class="user-dropdown">
              <el-icon><UserFilled /></el-icon>
              {{ userStore.nickname }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="me">当前账号：{{ userStore.userInfo?.username }}</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 菜单由路由表推导：meta.roles 不含当前用户角色的项直接不渲染 */
const menus = computed(() => {
  const layoutRoute = router.options.routes.find((r) => r.path === '/')
  return (layoutRoute?.children || [])
    .filter((child) => !child.meta?.roles || child.meta.roles.some((role) => userStore.hasRole(role)))
    .map((child) => ({
      path: `/${child.path}`,
      title: child.meta.title,
      icon: child.meta.icon
    }))
})

const activeMenu = computed(() => route.path)

async function onCommand(command) {
  if (command !== 'logout') return
  try {
    await ElMessageBox.confirm('确认退出登录？退出后当前 token 立即失效。', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户点了取消
  }
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background-color: #1f2d3d;
  display: flex;
  flex-direction: column;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 64px;
  padding: 0 16px;
  color: #fff;
  font-size: 22px;
  border-bottom: 1px solid #2c3e50;
}

.logo-text {
  line-height: 1.2;
}

.logo-title {
  font-size: 15px;
  font-weight: 600;
}

.logo-sub {
  font-size: 12px;
  color: #8a97a8;
}

.aside :deep(.el-menu) {
  border-right: none;
  flex: 1;
}

.aside-footer {
  padding: 12px 16px;
  font-size: 12px;
  color: #6b7a8d;
  line-height: 1.6;
  border-top: 1px solid #2c3e50;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.06);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: #303133;
  outline: none;
}

.main {
  background-color: #f5f7fa;
  padding: 16px;
}
</style>
