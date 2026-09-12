import request from './request'

/** 部门列表（ADMIN/EMPLOYEE 均可查看） */
export function listDepts() {
  return request.get('/depts')
}

/** 新增部门（仅 ADMIN） */
export function createDept(data) {
  return request.post('/depts', data)
}

/** 修改部门（仅 ADMIN） */
export function updateDept(id, data) {
  return request.put(`/depts/${id}`, data)
}

/** 删除部门（仅 ADMIN；部门下有员工会返回 409） */
export function deleteDept(id) {
  return request.delete(`/depts/${id}`)
}
