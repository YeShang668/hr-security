<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="姓名 / 工号"
            clearable
            style="width: 150px"
            @keyup.enter="load(1)"
          />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="query.deptId" placeholder="全部" clearable style="width: 120px">
            <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" />
          </el-select>
        </el-form-item>
        <!-- 身份证是密文存储，没法模糊查；这里走哈希等值匹配（演示"加密后仍可精确检索"） -->
        <el-form-item label="身份证">
          <el-input
            v-model="query.idCard"
            placeholder="精确匹配（加密字段）"
            clearable
            style="width: 170px"
            @keyup.enter="onSearchIdCard"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="onSearchIdCard">查询</el-button>
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

    <el-alert
      class="hint"
      type="info"
      :closable="false"
      show-icon
      title="敏感字段（手机号 / 身份证 / 银行卡 / 工资）在数据库里是 AES-256-GCM 密文；列表与详情统一返回脱敏值（管理员也一样），查看明文需点「查看完整信息」，后端会校验 employee:sensitive:read 权限。"
    />

    <el-table :data="rows" v-loading="loading" border stripe size="default">
      <el-table-column prop="empNo" label="工号" width="90" />
      <el-table-column prop="name" label="姓名" width="90" />
      <el-table-column label="性别" width="70">
        <template #default="{ row }">{{ row.gender === 2 ? '女' : '男' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号(脱敏)" width="125" />
      <el-table-column prop="idCard" label="身份证(脱敏)" min-width="170" />
      <el-table-column prop="bankCard" label="银行卡(脱敏)" min-width="170" show-overflow-tooltip />
      <el-table-column prop="salary" label="工资(脱敏)" width="100" />
      <el-table-column prop="deptName" label="部门" width="95" />
      <el-table-column prop="entryDate" label="入职日期" width="110" />
      <el-table-column label="状态" width="85">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '在职' : '离职' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="205" fixed="right">
        <template #default="{ row }">
          <!-- 有敏感权限才显示该按钮；没权限的角色即使看到按钮，后端也会返回 403 -->
          <el-button v-if="row.sensitiveVisible" link type="warning" @click="openSensitive(row)">
            查看完整信息
          </el-button>
          <el-button v-if="userStore.isAdmin" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="userStore.isAdmin" link type="danger" @click="onDelete(row)">离职</el-button>
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

    <!-- 敏感信息明文弹窗：一次显式、可被审计的访问 -->
    <el-dialog v-model="sensitiveDialog" title="敏感信息明文" width="480px">
      <el-alert
        class="hint"
        type="warning"
        :closable="false"
        show-icon
        title="这是一次显式访问：后端已校验 employee:sensitive:read 权限，第 7 周起由审计日志记录操作者、时间与 IP。"
      />
      <el-descriptions :column="1" border>
        <el-descriptions-item label="工号">{{ sensitive.empNo }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ sensitive.name }}</el-descriptions-item>
        <el-descriptions-item label="身份证号">{{ sensitive.idCard || '未录入' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ sensitive.phone || '未录入' }}</el-descriptions-item>
        <el-descriptions-item label="银行卡号">{{ sensitive.bankCard || '未录入' }}</el-descriptions-item>
        <el-descriptions-item label="工资">{{ sensitive.salary || '未录入' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="sensitiveDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑员工' : '新增员工'" width="560px">
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
          <el-input v-model="form.phone" :placeholder="sensitivePlaceholder('13800000001')" />
        </el-form-item>
        <el-form-item label="身份证" prop="idCard">
          <el-input v-model="form.idCard" :placeholder="sensitivePlaceholder('18 位身份证号')" />
        </el-form-item>
        <el-form-item label="银行卡" prop="bankCard">
          <el-input v-model="form.bankCard" :placeholder="sensitivePlaceholder('16~19 位卡号')" />
        </el-form-item>
        <el-form-item label="工资" prop="salary">
          <el-input v-model="form.salary" :placeholder="sensitivePlaceholder('18000.00')" />
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
      <el-alert
        v-if="form.id"
        class="hint"
        type="info"
        :closable="false"
        show-icon
        title="编辑时敏感字段留空 = 保持原值（列表里的脱敏值不会被写回数据库；即使误传掩码，后端也会以 400 拦住）。"
      />
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
import {
  createEmployee, deleteEmployee, getEmployeeSensitive,
  pageEmployees, searchEmployeeByIdCard, updateEmployee
} from '@/api/employee'
import { listDepts } from '@/api/dept'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const depts = ref([])
const query = reactive({ page: 1, size: 10, keyword: '', deptId: null, idCard: '' })

const dialogVisible = ref(false)
const sensitiveDialog = ref(false)
const sensitive = reactive({ empNo: '', name: '', idCard: '', phone: '', bankCard: '', salary: '' })
const formRef = ref()
const emptyForm = () => ({
  id: null,
  empNo: '',
  name: '',
  gender: 1,
  phone: '',
  idCard: '',
  bankCard: '',
  salary: '',
  email: '',
  deptId: null,
  entryDate: ''
})
const form = reactive(emptyForm())

const rules = {
  empNo: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  deptId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  // 前端只做即时提示，真正的格式校验在后端 DTO（掩码值/非法值一律 400）
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  idCard: [{
    pattern: /^$|^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[0-9Xx]$/,
    message: '身份证号格式不正确',
    trigger: 'blur'
  }],
  bankCard: [{ pattern: /^$|^\d{16,19}$/, message: '银行卡号应为 16~19 位数字', trigger: 'blur' }],
  salary: [{ pattern: /^$|^\d{1,8}(\.\d{1,2})?$/, message: '工资格式不正确（最多 8 位整数、2 位小数）', trigger: 'blur' }]
}

/** 编辑时敏感字段留空表示不修改，用不同占位文案提醒 */
function sensitivePlaceholder(sample) {
  return form.id ? '留空表示不修改' : `如 ${sample}`
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

/** 按身份证精确查：命中则只显示该员工；输入框清空后走普通分页列表 */
async function onSearchIdCard() {
  if (!query.idCard) {
    load(1)
    return
  }
  loading.value = true
  try {
    const res = await searchEmployeeByIdCard(query.idCard.trim())
    rows.value = res.data
    total.value = res.data.length
    if (res.data.length === 0) {
      ElMessage.info('未找到该身份证对应的员工（加密字段按哈希精确匹配，需输入完整号码）')
    }
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
  query.deptId = null
  query.idCard = ''
  load(1)
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

/**
 * 编辑回填：刻意**不回填敏感字段**。
 * 列表里的值是脱敏后的（如 138****2222），一旦回填进表单再提交，
 * 掩码就会被当成新明文加密写库，真实号码被覆盖丢失——
 * 这是"加密 + 脱敏"系统最常见的坑，前后端各设一道防线。
 */
function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    empNo: row.empNo,
    name: row.name,
    gender: row.gender,
    phone: '',
    idCard: '',
    bankCard: '',
    salary: '',
    email: row.email,
    deptId: row.deptId,
    entryDate: row.entryDate
  })
  dialogVisible.value = true
}

async function openSensitive(row) {
  try {
    const res = await getEmployeeSensitive(row.id)
    Object.assign(sensitive, res.data)
    sensitiveDialog.value = true
  } catch (e) {
    // 无权限(403)/解密失败已由拦截器提示
  }
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = { ...form }
    delete payload.id
    // 敏感字段留空就不提交（后端语义：不修改），避免把空串当成"清空"
    if (!payload.phone) delete payload.phone
    if (!payload.idCard) delete payload.idCard
    if (!payload.bankCard) delete payload.bankCard
    if (payload.salary === '' || payload.salary === null) {
      delete payload.salary
    } else {
      payload.salary = Number(payload.salary)
    }
    const res = form.id ? await updateEmployee(form.id, payload) : await createEmployee(payload)
    ElMessage.success(res.message)
    dialogVisible.value = false
    load()
  } catch (e) {
    // 工号/身份证重复等业务错误已由拦截器提示，弹窗保持打开便于修改
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

.hint {
  margin-bottom: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
