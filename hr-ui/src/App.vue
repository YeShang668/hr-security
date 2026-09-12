<template>
  <router-view />
</template>

<style>
/* 全局基础样式：Element Plus 自带组件样式，这里只做布局底座 */
* {
  box-sizing: border-box;
}

html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}

body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  background-color: #f5f7fa;
  color: #303133;
}
</style>
