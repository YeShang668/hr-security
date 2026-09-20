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
            <span class="card-title">本周演示剧本（第 6 周：敏感字段加密 + 动态脱敏）</span>
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
  { tag: '1. 直连数据库看密文', text: '员工管理列表里的手机号/身份证/银行卡/工资都是脱敏值；直接查库看到的是 v1:k1:... 密文，明文列已不存在', type: 'primary' },
  { tag: '2. 查看完整信息（显式明文）', text: '点「查看完整信息」才返回明文，后端校验 employee:sensitive:read 权限；zhangsan 没有该按钮，接口直调也是 403', type: 'warning' },
  { tag: '3. 密文不可模糊查', text: '关键字框搜手机号/身份证搜不到（密文没法 like）；改用「身份证」精确查询，走 HMAC 哈希匹配命中', type: 'success' },
  { tag: '4. 历史数据加密迁移', text: '模拟旧系统明文表 legacy_employee_plain → 一键 /api/admin/crypto/backfill 加密刷入，重复执行 migrated=0（幂等）', type: 'danger' },
  { tag: '5. 篡改即被发现', text: '手工改一位密文，GCM 认证标签校验失败，接口直接报错而不是返回乱码（完整性保护）', type: 'info' }
]

const securityPoints = [
  { title: '字段级加密', desc: 'AES-256-GCM（JDK javax.crypto）+ 每次随机 12 字节 IV + 128 位认证标签', icon: 'Lock' },
  { title: '动静自动加解密', desc: 'MyBatis-Plus TypeHandler 接管读写，业务代码只见明文、库里只有密文', icon: 'Switch' },
  { title: '动态脱敏', desc: '列表/详情一律脱敏（连 ADMIN 也一样），明文只从一次显式请求的出口出', icon: 'View' },
  { title: '可检索性设计', desc: '身份证存 HMAC-SHA256 盲索引，精确查询与唯一校验都能做，且不可逆', icon: 'Search' }
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
