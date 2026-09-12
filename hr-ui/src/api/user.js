import request from './request'

/** 用户分页列表（仅 ADMIN）：GET /api/users */
export function pageUsers(params) {
  return request.get('/users', { params })
}

/** 启用/禁用账号：PUT /api/users/{id}/status */
export function updateUserStatus(id, status) {
  return request.put(`/users/${id}/status`, { status })
}

/** 分配角色（覆盖式）：PUT /api/users/{id}/roles */
export function updateUserRoles(id, roleIds) {
  return request.put(`/users/${id}/roles`, { roleIds })
}

/** 全部启用角色：GET /api/roles（用户管理页的角色下拉框） */
export function listRoles() {
  return request.get('/roles')
}
