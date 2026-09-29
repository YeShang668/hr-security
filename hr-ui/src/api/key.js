import request from './request'

/** 密钥列表：GET /api/admin/keys（只有 keyId/状态/指纹/时间，不含任何密钥材料） */
export function listKeys() {
  return request.get('/admin/keys')
}

/** 轮换：生成新 DEK 并置为 ACTIVE，旧 DEK 置 RETIRED（老密文仍可解） */
export function rotateKey() {
  return request.post('/admin/keys/rotate')
}

/**
 * 存量数据重加密（轮换收尾）：POST /api/admin/keys/reencrypt
 * 幂等、可重复调用；未跑完（remaining>0）时再调一次即可续跑
 */
export function reencryptData(params) {
  return request.post('/admin/keys/reencrypt', null, { params })
}

/** 停用密钥（仅当已无任何密文引用它，否则后端 400 拒绝） */
export function disableKey(keyId) {
  return request.post(`/admin/keys/${keyId}/disable`)
}
