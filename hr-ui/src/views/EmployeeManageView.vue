<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="姓名 / 工号"
            clearable
            style="width: 180px"
            @keyup.enter="load(1)"
          />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="query.deptId" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="load(1)">查询</el-button>
          <el-button :icon="Refresh" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar-right">
        <el-tag v-if="!userStore.isAdmin" type="warning" effect="plain">
          只读账号：增删改按钮已隐藏（后端同样会拦 403）
        </el-tag>
        <el-button v-if="userStore.isAdmin" type="primary" :icon="Plus" @click="openCreate">
          新增员工
        </el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="default">
      <el-table-column prop="empNo" label="工号" width="100" />
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column label="性别" width="80">
        <template #default="{ row }">{{ row.gender === 2 ? '女' : '男' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" width="130" />
      <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
      <el-table-column prop="deptName" label="部门" width="110" />
      <el-table-column prop="entryDate" label="入职日期" width="120" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '在职' : '离职' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="userStore.isAdmin" label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">离职</el-button>
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑员工' : '新增员工'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="工号" prop="empNo">
          <el-input v-model="form.empNo" placeholder="如 E005" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="性别" prop="gender">
          <el-radio-group v-model="form.gender">
            <el-radio :value="1">男</el-radio>
            <el-radio :value="2">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="开发阶段明文存储，9/19 起加密" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="部门" prop="deptId">
          <el-select v-model="form.deptId" placeholder="请选择部门" style="width: 100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入职日期" prop="entryDate">
          <el-date-picker
            v-model="form.entryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
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
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { createEmployee, deleteEmployee, pageEmployees, updateEmployee } from '@/api/employee'
import { listDepts } from '@/api/dept'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const depts = ref([])
const query = reactive({ page: 1, size: 10, keyword: '', deptId: null })

const dialogVisible = ref(false)
const formRef = ref()
const emptyForm = () => ({
  id: null,
  empNo: '',
  name: '',
  gender: 1,
  phone: '',
  email: '',
  deptId: null,
  entryDate: ''
})
const form = reactive(emptyForm())

const rules = {
  empNo: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  deptId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function load(page = query.page) {
  query.page = page
  loading.value = true
  try {
    const res = await pageEmployees({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      deptId: query.deptId || undefined
    })
    rows.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 已在拦截器统一提示
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
  query.deptId = null
  load(1)
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
    const payload = { ...form }
    delete payload.id
    const res = form.id ? await updateEmployee(form.id, payload) : await createEmployee(payload)
    ElMessage.success(res.message)
    dialogVisible.value = false
    load()
  } catch (e) {
    // 工号重复等业务错误已由拦截器提示，弹窗保持打开便于修改
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.name}（${row.empNo}）」办理离职？逻辑删除，数据保留可追溯。`,
      '离职确认',
      { type: 'warning', confirmButtonText: '确认离职', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await deleteEmployee(row.id)
    ElMessage.success(res.message)
    load()
  } catch (e) {
    // 已提示
  }
}

onMounted(async () => {
  load(1)
  try {
    const res = await listDepts()
    depts.value = res.data
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
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
