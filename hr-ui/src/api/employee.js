import request from './request'

/** 员工分页列表：GET /api/employees?page&size&keyword&deptId（敏感字段为脱敏值） */
export function pageEmployees(params) {
  return request.get('/employees', { params })
}

/** 员工详情（敏感字段为脱敏值） */
export function getEmployee(id) {
  return request.get(`/employees/${id}`)
}

/** 敏感信息明文：GET /api/employees/{id}/sensitive（需 employee:sensitive:read 权限） */
export function getEmployeeSensitive(id) {
  return request.get(`/employees/${id}/sensitive`)
}

/** 按身份证号精确查（密文不可模糊查，后端用 HMAC 哈希等值匹配） */
export function searchEmployeeByIdCard(idCard) {
  return request.get('/employees/search', { params: { idCard } })
}

/** 新增员工（仅 ADMIN）：敏感字段明文传入，后端加密落库 */
export function createEmployee(data) {
  return request.post('/employees', data)
}

/** 修改员工（仅 ADMIN）：敏感字段留空表示不修改 */
export function updateEmployee(id, data) {
  return request.put(`/employees/${id}`, data)
}

/** 删除员工（仅 ADMIN，实为逻辑删除=离职） */
export function deleteEmployee(id) {
  return request.delete(`/employees/${id}`)
}
