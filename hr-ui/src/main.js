import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import pinia from './stores'

const app = createApp(App)

// Element Plus 全量引入：演示项目优先"少配置、能跑通"，不折腾按需引入
app.use(ElementPlus, { locale: zhCn })
app.use(pinia)
app.use(router)

// 图标全局注册（侧边栏菜单用字符串指定图标名，这样写最省事）
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.mount('#app')
