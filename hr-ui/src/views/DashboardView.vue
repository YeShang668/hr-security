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
            <span class="card-title">本周演示剧本（第 7 周：审计日志 + KEK/DEK 密钥管理）</span>
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
          @PreAuthorize（例如 /api/users、/api/audit-logs、/api/admin/keys 仅 ADMIN），
          普通用户即使手工调接口也会被拦成 403。
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
  if (userStore.isAdmin) menus.push('用户管理', '审计日志', '密钥管理')
  return menus
})

const demoSteps = [
  { tag: '1. 看一眼敏感明文', text: '员工管理点「查看完整信息」——这一步在审计里留痕；换 zhangsan 登录连按钮都没有，直调接口 403', type: 'primary' },
  { tag: '2. 审计日志查这一步', text: '「审计日志」页按操作类型搜"敏感"：能看到谁(admin)、何时、看了哪个员工、来自哪个 IP；明细只有字段名没有明文值', type: 'warning' },
  { tag: '3. 失败也有记录', text: '把筛选切到"失败"：越权尝试、被红线拦下的操作（如禁用自己）同样有记录，还带失败原因', type: 'info' },
  { tag: '4. 翻账也留痕', text: '每次查询审计日志本身也会写一条审计（operation=查询审计日志），避免"内鬼先把账翻一遍"成为盲区', type: 'info' },
  { tag: '5. 轮换密钥不停机', text: '「密钥管理」点轮换：新密文立刻用 k2，老密文（k1/RETIRED）照样读得出；再点重加密，残留归零，最后停用 k1（还有残留会被拒绝）', type: 'danger' },
  { tag: '6. 密钥材料不落库', text: 'sys_data_key 里只有被 KEK 信封加密后的 DEK 密文；KEK 本身在环境变量里，换 KEK 会直接报错而不是静默解不开', type: 'success' }
]

const securityPoints = [
  { title: '审计可追责', desc: 'AOP 注解埋点（明文查看/权限变更/密钥运维），记录 谁·何时·做了什么·对什么·来自哪', icon: 'Document' },
  { title: '异步不拖慢业务', desc: '@Async 自定义线程池落库；上下文（用户/IP）在切面先从 ThreadLocal 取出来再传参', icon: 'Timer' },
  { title: 'KEK/DEK 两级密钥', desc: 'KEK 只在环境变量（不落库），DEK 密文存库；换 DEK 不动 KEK、不停机', icon: 'Key' },
  { title: '轮换四步闭环', desc: '新密钥上线 → 老密文仍可解 → 分批幂等重加密 → 残留为 0 才允许停用旧密钥', icon: 'Refresh' }
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
