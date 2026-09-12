<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <span class="card-title">当前登录身份</span>
          </template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="用户名">{{ userStore.userInfo?.username }}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{ userStore.userInfo?.nickname }}</el-descriptions-item>
            <el-descriptions-item label="角色">
              <el-tag
                v-for="role in userStore.roles"
                :key="role"
                :type="role === 'ADMIN' ? 'danger' : 'info'"
                size="small"
                class="role-tag"
              >
                {{ role }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="账号创建时间">
              {{ userStore.userInfo?.createdAt }}
            </el-descriptions-item>
            <el-descriptions-item label="当前菜单权限">
              <el-tag v-for="m in myMenus" :key="m" size="small" effect="plain" class="role-tag">
                {{ m }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <el-alert type="success" :closable="false" class="mt-12">
            <template #title>
              角色变更即时生效：管理员在「用户管理」改我的角色后，我不需要重新登录，
              下一个请求就按新角色鉴权（服务端已删 user:roles:{id} 缓存）。
            </template>
          </el-alert>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <span class="card-title">本周演示剧本（第 5 周：前端 + 用户管理）</span>
          </template>
          <el-timeline>
            <el-timeline-item
              v-for="(item, index) in demoSteps"
              :key="index"
              :timestamp="item.tag"
              placement="top"
              :type="item.type"
            >
              {{ item.text }}
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mt-16">
      <template #header>
        <span class="card-title">安全设计要点（面试常问）</span>
      </template>
      <el-row :gutter="16">
        <el-col v-for="point in securityPoints" :key="point.title" :span="6">
          <div class="point">
            <div class="point-title">
              <el-icon><component :is="point.icon" /></el-icon>
              {{ point.title }}
            </div>
            <div class="point-desc">{{ point.desc }}</div>
          </div>
        </el-col>
      </el-row>
      <el-alert type="warning" :closable="false" class="mt-12">
        <template #title>
          前端隐藏菜单只是"看不见"，不是"不能做"：权限判断在后端
          @PreAuthorize（例如 /api/users 仅 ADMIN），普通用户即使手工调接口也会被拦成 403。
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const myMenus = computed(() => {
  const menus = ['首页']
  if (userStore.hasRole('ADMIN') || userStore.hasRole('EMPLOYEE')) menus.push('员工管理', '部门管理')
  if (userStore.isAdmin) menus.push('用户管理')
  return menus
})

const demoSteps = [
  { tag: '1. 登录', text: 'admin/123456 登录，菜单含「用户管理」；zhangsan 登录看不到该菜单', type: 'primary' },
  { tag: '2. 前端隐藏 ≠ 安全', text: '用 zhangsan 的 token 直接请求 /api/users，后端返回 403 无权限', type: 'warning' },
  { tag: '3. 角色分配', text: '给 zhangsan 加 ADMIN 角色 → 他不重新登录，旧 token 立即出现「新增员工」按钮', type: 'success' },
  { tag: '4. 禁用即踢下线', text: '禁用 zhangsan → 他手里的 token 下一个请求直接 401 被踢回登录页', type: 'danger' },
  { tag: '5. 登出即失效', text: '退出登录后旧 token 立即 401（Redis 会话被删）', type: 'info' }
]

const securityPoints = [
  { title: '认证', desc: 'BCrypt 存密码 + JWT 24h + Redis 会话，登出/禁用即失效', icon: 'Key' },
  { title: '鉴权', desc: 'RBAC 角色 + @PreAuthorize 接口级控制，前端仅做展示控制', icon: 'Lock' },
  { title: '缓存一致性', desc: '角色变更删 user:roles 缓存 + 30 分钟兜底 TTL', icon: 'Refresh' },
  { title: '待加密项', desc: '身份证/手机号/工资等字段 9/19 起 AES-256-GCM 加密 + 脱敏', icon: 'Warning' }
]
</script>

<style scoped>
.card-title {
  font-weight: 600;
}

.role-tag {
  margin-right: 6px;
}

.mt-12 {
  margin-top: 12px;
}

.mt-16 {
  margin-top: 16px;
}

.point {
  padding: 8px 12px;
  border-left: 3px solid #416a9d;
  background-color: #f7f9fc;
  border-radius: 4px;
  height: 100%;
}

.point-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  font-size: 13px;
  color: #2f3a45;
  margin-bottom: 4px;
}

.point-desc {
  font-size: 12px;
  color: #6b7a8d;
  line-height: 1.6;
}
</style>
