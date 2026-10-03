<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import router from './router'
import { farmApi } from './api'
import EnvironmentDashboard from './views/EnvironmentDashboard.vue'
import EnvironmentTickets from './views/EnvironmentTickets.vue'
import FruitingTimeline from './views/FruitingTimeline.vue'
import ContaminationGrid from './views/ContaminationGrid.vue'
import RackModel from './views/RackModel.vue'

const commands = [
  { label: '菇房环境仪表盘', short: '环境', path: '/environment', hint: '温湿度 · CO₂ · 24h' },
  { label: '环境越限处置单', short: '处置', path: '/tickets', hint: '触发 · 接单 · 解除' },
  { label: '出菇潮次时间轴', short: '潮次', path: '/fruiting', hint: '一潮 · 二潮 · 三潮' },
  { label: '菌包污染排查', short: '污染', path: '/contamination', hint: '批量标记 · 处理' },
  { label: '培养架层架立体图', short: '层架', path: '/racks', hint: '格位 · 上架率' }
]
const components = {
  '/environment': EnvironmentDashboard,
  '/tickets': EnvironmentTickets,
  '/fruiting': FruitingTimeline,
  '/contamination': ContaminationGrid,
  '/racks': RackModel
}

const commandRef = ref()
const currentPath = ref(components[window.location.pathname] ? window.location.pathname : '/environment')
const command = ref(currentPath.value)
const overview = ref({ roomCount: 0, batchCount: 0 })
const activeCommand = computed(() => commands.find(item => item.path === currentPath.value) || commands[0])
const activeView = computed(() => components[currentPath.value] || EnvironmentDashboard)

async function navigate(path) {
  if (!components[path]) return
  currentPath.value = path
  command.value = path
  await router.push(path).catch(() => {})
}

function onShortcut(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    commandRef.value?.focus()
  }
}

function onPopstate() {
  currentPath.value = components[window.location.pathname] ? window.location.pathname : '/environment'
  command.value = currentPath.value
}

onMounted(async () => {
  await navigate(currentPath.value)
  window.addEventListener('keydown', onShortcut)
  window.addEventListener('popstate', onPopstate)
  try { overview.value = await farmApi.getOverview() } catch (_) { /* 页面业务区负责展示接口错误 */ }
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  window.removeEventListener('popstate', onPopstate)
})
</script>

<template>
  <div class="app-shell">
    <header class="command-header">
      <div class="brand-lockup">
        <div class="brand-seal">菌</div>
        <div>
          <strong>食用菌工厂</strong>
          <span>菇房与出菇批次管理</span>
        </div>
      </div>

      <div class="command-palette">
        <span class="command-prefix">/</span>
        <el-select
          ref="commandRef"
          v-model="command"
          filterable
          placeholder="输入模块名称或命令…"
          class="command-select"
          @change="navigate"
        >
          <el-option v-for="item in commands" :key="item.path" :label="item.label" :value="item.path">
            <div class="command-option">
              <strong>{{ item.label }}</strong>
              <span>{{ item.hint }}</span>
            </div>
          </el-option>
        </el-select>
        <kbd>⌘K</kbd>
      </div>

      <div class="header-context">
        <div class="module-name"><span>当前模块</span><strong>{{ activeCommand.short }}</strong></div>
        <div class="header-stats">
          <div><b>{{ overview.roomCount }}</b><span>菇房</span></div>
          <i></i>
          <div><b>{{ overview.batchCount }}</b><span>活跃批次</span></div>
        </div>
      </div>
    </header>

    <main class="workspace">
      <component :is="activeView" />
    </main>
  </div>
</template>
