<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="操作者">
          <el-input
            v-model="query.username"
            placeholder="用户名，如 admin"
            clearable
            style="width: 160px"
            @keyup.enter="load(1)"
          />
        </el-form-item>
        <el-form-item label="操作类型">
          <el-input
            v-model="query.operation"
            placeholder="如 敏感 / 角色"
            clearable
            style="width: 160px"
            @keyup.enter="load(1)"
          />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.result" placeholder="全部" clearable style="width: 120px">
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILURE" />
          </el-select>
        </el-form-item>
        <el-form-item label="对象类型">
          <el-select v-model="query.targetType" placeholder="全部" clearable style="width: 140px">
            <el-option label="员工" value="EMPLOYEE" />
            <el-option label="部门" value="DEPT" />
            <el-option label="用户" value="USER" />
            <el-option label="加密运维" value="CRYPTO" />
            <el-option label="密钥" value="KEY" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="load(1)">查询</el-button>
          <el-button :icon="Refresh" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-tag type="danger" effect="plain">本页仅 ADMIN 可见（后端 /api/audit-logs 仅 ADMIN）</el-tag>
    </div>

    <el-alert type="info" :closable="false" class="mb-12">
      <template #title>
        审计记录只增不改：谁在什么时候看了谁的敏感明文、改过谁的权限、动过哪把密钥，都在这里。
        明细里刻意不写身份证/手机号等明文值，只写"看过/改过哪些字段"，否则审计表自己就变成一张明文敏感数据表。
      </template>
    </el-alert>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column label="操作者" width="110">
        <template #default="{ row }">
          <span v-if="row.username">{{ row.username }}</span>
          <span v-else class="muted">（无）</span>
          <span class="muted"> #{{ row.userId ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="operation" label="操作" width="150" show-overflow-tooltip />
      <el-table-column label="对象" width="150">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.targetType || '-' }}</el-tag>
          <span class="muted">{{ row.targetId ? ' #' + row.targetId : '' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ip" label="来源 IP" width="150" show-overflow-tooltip />
      <el-table-column prop="detail" label="明细" min-width="260" show-overflow-tooltip />
    </el-table>

    <el-pagination
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="query.page"
      :page-size="query.size"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { pageAuditLogs } from '@/api/audit'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 10,
  username: '',
  operation: '',
  result: null,
  targetType: null
})

async function load(page = query.page) {
  query.page = page
  loading.value = true
  try {
    const res = await pageAuditLogs({
      page: query.page,
      size: query.size,
      username: query.username || undefined,
      operation: query.operation || undefined,
      result: query.result || undefined,
      targetType: query.targetType || undefined
    })
    rows.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 已统一提示
  } finally {
    loading.value = false
  }
}

function onSizeChange(size) {
  query.size = size
  load(1)
}

function onReset() {
  query.username = ''
  query.operation = ''
  query.result = null
  query.targetType = null
  load(1)
}

onMounted(() => load(1))
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.muted {
  color: #a8abb2;
  font-size: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
