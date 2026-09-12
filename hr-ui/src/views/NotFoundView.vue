<template>
  <div class="page">
    <el-result icon="error" title="404 页面不存在" sub-title="请检查地址是否正确">
      <template #extra>
        <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.page {
  max-width: 720px;
  margin: 60px auto;
}
</style>
