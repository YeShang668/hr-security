import request from './request'

/** 登录：POST /api/auth/login → {token, user, roles} */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 注册：POST /api/auth/register */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 当前用户：GET /api/auth/me（角色以这里为准，刷新页面用它恢复登录态） */
export function getMe() {
  return request.get('/auth/me')
}

/** 登出：POST /api/auth/logout（服务端删 Redis 会话，旧 token 立即失效） */
export function logout() {
  return request.post('/auth/logout')
}
