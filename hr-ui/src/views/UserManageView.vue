<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="用户名 / 姓名"
            clearable
            style="width: 180px"
            @keyup.enter="load(1)"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="load(1)">查询</el-button>
          <el-button :icon="Refresh" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-tag type="danger" effect="plain">本页仅 ADMIN 可见（后端 /api/users 仅 ADMIN）</el-tag>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="nickname" label="姓名" width="110" />
      <el-table-column label="角色" min-width="150">
        <template #default="{ row }">
          <el-tag
            v-for="role in row.roles"
            :key="role"
            :type="role === 'ADMIN' ? 'danger' : 'info'"
            size="small"
            class="role-tag"
          >
            {{ role }}
          </el-tag>
          <span v-if="!row.roles.length" class="muted">未分配</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status"
            :active-value="1"
            :inactive-value="0"
            :disabled="isSelfOrBuiltin(row)"
            :loading="statusLoadingId === row.id"
            active-text="启用"
            inactive-text="禁用"
            inline-prompt
            @change="(val) => onChangeStatus(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="190" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="isSelf(row)" @click="openRoles(row)">
            分配角色
          </el-button>
          <el-tooltip v-if="isSelf(row)" content="不能修改当前登录账号的角色" placement="top">
            <span class="muted">（不能改自己）</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="query.page"
      :page-size="query.size"
      :page-sizes="[10, 20, 50]"
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="roleDialogVisible" title="分配角色" width="460px">
      <el-alert type="info" :closable="false" class="mb-12">
        <template #title>
          改成"管理员"后，该用户不需要重新登录，下一个请求就获得管理员权限
          （服务端已删其角色缓存）；反之立即失去权限。
        </template>
      </el-alert>
      <el-form label-width="80px">
        <el-form-item label="用户">
          <el-tag type="info">{{ roleForm.username }}（{{ roleForm.nickname }}）</el-tag>
        </el-form-item>
        <el-form-item label="角色">
          <el-checkbox-group v-model="roleForm.roleIds">
            <el-checkbox v-for="r in roles" :key="r.id" :value="r.id">
              {{ r.roleName }}（{{ r.roleCode }}）
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveRoles">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listRoles, pageUsers, updateUserRoles, updateUserStatus } from '@/api/user'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const statusLoadingId = ref(null)
const rows = ref([])
const total = ref(0)
const roles = ref([])
const query = reactive({ page: 1, size: 10, keyword: '', status: null })

const roleDialogVisible = ref(false)
const roleForm = reactive({ id: null, username: '', nickname: '', roleIds: [] })

/** 当前登录账号：不能禁用自己、不能改自己的角色（后端也会拦） */
const isSelf = (row) => row.id === userStore.userInfo?.id
/** 内置 admin 账号保留（后端同样限制） */
const isBuiltin = (row) => row.username === 'admin'
const isSelfOrBuiltin = (row) => isSelf(row) || isBuiltin(row)

async function load(page = query.page) {
  query.page = page
  loading.value = true
  try {
    const res = await pageUsers({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status === null ? undefined : query.status
    })
    rows.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 已提示
  } finally {
    loading.value = false
  }
}

function onSizeChange(size) {
  query.size = size
  load(1)
}

function onReset() {
  query.keyword = ''
  query.status = null
  load(1)
}

async function onChangeStatus(row, value) {
  const label = value === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      value === 0
        ? `确认禁用「${row.username}」？该账号当前所有登录会话会被立即踢下线，且无法再登录。`
        : `确认启用「${row.username}」？`,
      `${label}确认`,
      { type: value === 0 ? 'warning' : 'info', confirmButtonText: label, cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 取消：开关保持原值（model-value 单向绑定，不会脏改）
  }
  statusLoadingId.value = row.id
  try {
    const res = await updateUserStatus(row.id, value)
    ElMessage.success(res.message)
    load()
  } catch (e) {
    load() // 失败时刷新回真实状态
  } finally {
    statusLoadingId.value = null
  }
}

function openRoles(row) {
  roleForm.id = row.id
  roleForm.username = row.username
  roleForm.nickname = row.nickname
  roleForm.roleIds = roles.value.filter((r) => row.roles.includes(r.roleCode)).map((r) => r.id)
  roleDialogVisible.value = true
}

async function onSaveRoles() {
  if (!roleForm.roleIds.length) {
    ElMessage.warning('至少选择一个角色')
    return
  }
  saving.value = true
  try {
    const res = await updateUserRoles(roleForm.id, roleForm.roleIds)
    ElMessage.success(res.message)
    roleDialogVisible.value = false
    load()
  } catch (e) {
    // 已提示
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  load(1)
  try {
    const res = await listRoles()
    roles.value = res.data
  } catch (e) {
    // 已提示
  }
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.role-tag {
  margin-right: 6px;
}

.muted {
  color: #a8abb2;
  font-size: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
