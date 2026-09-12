<template>
  <div class="page">
    <el-result icon="warning" title="403 无权限访问" sub-title="当前账号没有访问该页面的角色权限">
      <template #extra>
        <el-button type="primary" @click="$router.push('/dashboard')">返回首页</el-button>
      </template>
    </el-result>
    <el-alert type="info" :closable="false" class="note">
      <template #title>
        前端拦截只是为了体验（别让用户看到空白页），真正的权限校验在后端：
        每个接口都有 @PreAuthorize，手工构造请求同样会被拦下并返回 403。
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.page {
  max-width: 720px;
  margin: 40px auto;
}

.note {
  margin-top: 8px;
}
</style>
