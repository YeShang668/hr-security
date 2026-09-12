import request from './request'

/** 员工分页列表：GET /api/employees?page&size&keyword&deptId */
export function pageEmployees(params) {
  return request.get('/employees', { params })
}

/** 新增员工（仅 ADMIN） */
export function createEmployee(data) {
  return request.post('/employees', data)
}

/** 修改员工（仅 ADMIN） */
export function updateEmployee(id, data) {
  return request.put(`/employees/${id}`, data)
}

/** 删除员工（仅 ADMIN，实为逻辑删除=离职） */
export function deleteEmployee(id) {
  return request.delete(`/employees/${id}`)
}
