<template>
  <div class="login-page">
    <div class="login-box">
      <div class="login-brand">
        <el-icon :size="34"><Lock /></el-icon>
        <h1>人事敏感数据安全管控系统</h1>
        <p>AES-256 字段加密 · RBAC 最小权限 · 动态脱敏 · 审计日志</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Key"
            show-password
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="onSubmit">
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false" class="login-tip">
        <template #title>
          <div class="tip-line">演示账号（开发环境种子数据）</div>
          <div class="tip-line">管理员：admin / 123456 —— 可见全部菜单</div>
          <div class="tip-line">普通员工：zhangsan / 123456 —— 无用户管理菜单，员工/部门只读</div>
        </template>
      </el-alert>

      <p class="login-note">
        密码以 BCrypt 密文落库；登录成功后签发 24h JWT 并在 Redis 建会话（登出/禁用即失效）。
      </p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Key } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' }
  ]
}

async function onSubmit() {
  // 表单校验不通过直接返回，避免无意义的后端请求
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await userStore.login({ ...form })
    ElMessage.success(`欢迎回来，${userStore.nickname}`)
    // 登录前想去的页面（被守卫拦下来的那个）优先，否则进首页
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect ? redirect : '/')
  } catch (e) {
    // 错误提示已由 Axios 响应拦截器统一弹出，这里只需结束 loading
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2c5364 60%, #3a6ea5 100%);
}

.login-box {
  width: 420px;
  padding: 32px 36px 20px;
  background-color: #fff;
  border-radius: 8px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.25);
}

.login-brand {
  text-align: center;
  margin-bottom: 24px;
  color: #1f2d3d;
}

.login-brand h1 {
  font-size: 19px;
  margin: 8px 0 4px;
}

.login-brand p {
  margin: 0;
  font-size: 12px;
  color: #909399;
}

.login-btn {
  width: 100%;
}

.login-tip {
  margin-bottom: 12px;
}

.tip-line {
  font-size: 12px;
  line-height: 1.7;
}

.login-note {
  font-size: 12px;
  color: #a8abb2;
  text-align: center;
  line-height: 1.6;
  margin: 0;
}
</style>
