<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">数据加密密钥（DEK）</span>
          <div>
            <el-button type="primary" :icon="Refresh" :loading="rotating" @click="onRotate">轮换密钥</el-button>
            <el-button type="warning" :icon="MagicStick" :loading="reencrypting" @click="onReencrypt">
              执行重加密
            </el-button>
          </div>
        </div>
      </template>

      <el-alert type="warning" :closable="false" class="mb-12">
        <template #title>
          两级密钥（信封加密）：KEK 只存在于环境变量/KMS（本页永远看不到它），
          它加密的 DEK 才是真正加密业务字段的密钥，且只以密文形式存在 sys_data_key 表里。
          所以换 DEK 不用动 KEK、不用停机；表里也绝不会有明文密钥材料。
        </template>
      </el-alert>

      <el-steps :active="step" align-center finish-status="success" class="mb-12">
        <el-step title="生成新 DEK" description="轮换：新密钥上线" />
        <el-step title="老密文仍可解" description="旧 DEK 置 RETIRED" />
        <el-step title="分批重加密" description="幂等、可反复跑到残留 0" />
        <el-step title="停用旧 DEK" description="有残留则拒绝（报警点）" />
      </el-steps>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="keyId" label="keyId" width="80" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用途" min-width="180">
          <template #default="{ row }">
            <span v-if="row.status === 'ACTIVE'">新写入的密文都用它加密</span>
            <span v-else-if="row.status === 'RETIRED'">不再加密，但仍能解密老密文</span>
            <span v-else>已停用：拒绝解密（密钥材料已从内存卸载）</span>
          </template>
        </el-table-column>
        <el-table-column prop="dekFingerprint" label="DEK 指纹" width="110">
          <template #default="{ row }">
            <span v-if="row.dekFingerprint" class="mono">{{ row.dekFingerprint }}</span>
            <span v-else class="muted">—（不可用）</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="上线时间" width="175" />
        <el-table-column prop="retiredAt" label="退役时间" width="175">
          <template #default="{ row }">
            <span v-if="row.retiredAt">{{ row.retiredAt }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'RETIRED'"
              link
              type="danger"
              :loading="disablingId === row.keyId"
              @click="onDisable(row)"
            >
              停用
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert v-if="lastResult" :type="lastResult.allDone ? 'success' : 'warning'" :closable="false" class="mt-12">
        <template #title>
          重加密结果：目标密钥 {{ lastResult.activeKeyId }}，处理 {{ lastResult.batches }} 批，
          改写 {{ lastResult.reencrypted }} 行，残留 {{ lastResult.remaining }} 行
          （{{ lastResult.allDone ? '全库已无老密钥密文，可以停用旧密钥了' : '还有残留，可再点一次续跑' }}）。
        </template>
      </el-alert>

      <el-alert type="info" :closable="false" class="mt-12">
        <template #title>
          「停用」为什么必须放最后：还有老密文时就停用，等于让那批数据永远解不开。
          后端会先统计残留行数，不为 0 直接拒绝（并回报还有几行），这就是"轮换是否彻底"的验收点。
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, Refresh } from '@element-plus/icons-vue'
import { disableKey, listKeys, reencryptData, rotateKey } from '@/api/key'

const loading = ref(false)
const rotating = ref(false)
const reencrypting = ref(false)
const disablingId = ref(null)
const rows = ref([])
const lastResult = ref(null)

/** 步骤条位置：由当前密钥状态推导（有 RETIRED 说明轮换过；有 DISABLED 说明走完了完整流程） */
const step = computed(() => {
  if (rows.value.some((k) => k.status === 'DISABLED')) return 4
  if (rows.value.some((k) => k.status === 'RETIRED')) return 2
  return 1
})

const statusText = (status) =>
  ({ ACTIVE: 'ACTIVE（在用）', RETIRED: 'RETIRED（已退役）', DISABLED: 'DISABLED（已停用）' }[status] || status)

const statusType = (status) => ({ ACTIVE: 'success', RETIRED: 'warning', DISABLED: 'info' }[status] || 'info')

async function load() {
  loading.value = true
  try {
    const res = await listKeys()
    rows.value = res.data
  } catch (e) {
    // 已统一提示
  } finally {
    loading.value = false
  }
}

async function onRotate() {
  try {
    await ElMessageBox.confirm(
      '轮换会生成新的数据密钥：新写入立刻用新密钥，老密文仍可正常读取（旧密钥置为 RETIRED）。确认执行？',
      '轮换密钥',
      { type: 'warning', confirmButtonText: '轮换', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  rotating.value = true
  try {
    const res = await rotateKey()
    ElMessage.success(`轮换完成：${res.data.previousKeyId} → ${res.data.newKeyId}`)
    await load()
  } catch (e) {
    // 已统一提示
  } finally {
    rotating.value = false
  }
}

async function onReencrypt() {
  reencrypting.value = true
  try {
    const res = await reencryptData({ batchSize: 500, maxBatches: 100 })
    lastResult.value = res.data
    ElMessage.success(res.message)
    await load()
  } catch (e) {
    // 已统一提示
  } finally {
    reencrypting.value = false
  }
}

async function onDisable(row) {
  try {
    await ElMessageBox.confirm(
      `确认停用密钥「${row.keyId}」？停用后它将不能再解密任何数据（当前活性密钥不能停用）。`,
      '停用密钥',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  disablingId.value = row.keyId
  try {
    const res = await disableKey(row.keyId)
    ElMessage.success(res.message)
    await load()
  } catch (e) {
    // 有残留时后端会 400 并回报剩余行数，提示已由拦截器弹出
  } finally {
    disablingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.mono {
  font-family: Consolas, Monaco, monospace;
}

.muted {
  color: #a8abb2;
  font-size: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}

.mt-12 {
  margin-top: 12px;
}
</style>
