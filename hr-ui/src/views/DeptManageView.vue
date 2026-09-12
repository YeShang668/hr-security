<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div class="hint">
        部门列表按 sort 升序（多级树形组织架构留待后续演进）。部门下存在在职员工时不允许删除。
      </div>
      <el-button v-if="userStore.isAdmin" type="primary" :icon="Plus" @click="openCreate">
        新增部门
      </el-button>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="deptName" label="部门名称" min-width="180" />
      <el-table-column prop="sort" label="排序" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="190" show-overflow-tooltip />
      <el-table-column v-if="userStore.isAdmin" label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑部门' : '新增部门'" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="部门名称" prop="deptName">
          <el-input v-model="form.deptName" placeholder="如 技术部" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { createDept, deleteDept, listDepts, updateDept } from '@/api/dept'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const rows = ref([])

const dialogVisible = ref(false)
const formRef = ref()
const emptyForm = () => ({ id: null, deptName: '', sort: 0, status: 1 })
const form = reactive(emptyForm())

const rules = {
  deptName: [{ required: true, message: '请输入部门名称', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    const res = await listDepts()
    rows.value = res.data
  } catch (e) {
    // 已提示
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, { ...row })
  dialogVisible.value = true
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = { deptName: form.deptName, sort: form.sort, status: form.status }
    const res = form.id ? await updateDept(form.id, payload) : await createDept(payload)
    ElMessage.success(res.message)
    dialogVisible.value = false
    load()
  } catch (e) {
    // 已提示
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除部门「${row.deptName}」？`, '删除确认', {
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    const res = await deleteDept(row.id)
    ElMessage.success(res.message)
    load()
  } catch (e) {
    // 部门下有员工时后端返回 409，提示已弹出
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: #909399;
}
</style>
