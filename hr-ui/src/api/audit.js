import request from './request'

/**
 * 审计日志分页查询（仅 ADMIN）：GET /api/audit-logs
 * 支持 page/size/userId/username/operation/targetType/result/startTime/endTime
 */
export function pageAuditLogs(params) {
  return request.get('/audit-logs', { params })
}
