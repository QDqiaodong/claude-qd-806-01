<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const rooms = ref([])
const history = ref([])
const activeRoom = ref(null)
const loading = ref(false)
const drawerVisible = ref(false)

const speciesLimits = {
  金针菇: { temperature: [4, 12], humidity: [85, 95], co2_ppm: [0, 1200] },
  杏鲍菇: { temperature: [12, 18], humidity: [85, 95], co2_ppm: [0, 1500] },
  香菇: { temperature: [12, 20], humidity: [80, 95], co2_ppm: [0, 1500] },
  平菇: { temperature: [15, 22], humidity: [80, 95], co2_ppm: [0, 1800] }
}

const series = computed(() => [
  { key: 'temperature', label: '温度', color: '#827717', points: makePoints('temperature', 0, 30) },
  { key: 'humidity', label: '湿度', color: '#3e7e72', points: makePoints('humidity', 60, 100) },
  { key: 'co2Ppm', label: 'CO₂', color: '#c45b45', points: makePoints('co2Ppm', 0, 2600) }
])

async function load() {
  loading.value = true
  try { rooms.value = await farmApi.getEnvironmentDashboard() }
  catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

async function openHistory(room) {
  activeRoom.value = room
  drawerVisible.value = true
  try { history.value = await farmApi.getEnvironmentHistory(room.room_id, 24) }
  catch (error) { ElMessage.error(error.message) }
}

function metricAlert(room, key) {
  const range = speciesLimits[room.species]?.[key]
  if (!range) return false
  const value = Number(room[key])
  return value < range[0] || value > range[1]
}

function gaugeStyle(room, key) {
  const value = Number(room[key])
  const max = key === 'temperature' ? 30 : key === 'humidity' ? 100 : 2500
  return {
    '--gauge-value': `${Math.min(100, value / max * 100) * 3.6}deg`,
    '--gauge-color': metricAlert(room, key) ? '#c5473b' : '#827717'
  }
}

function makePoints(key, min, max) {
  if (!history.value.length) return ''
  return history.value.map((item, index) => {
    const x = 30 + index * (660 / Math.max(1, history.value.length - 1))
    const value = Math.max(min, Math.min(max, Number(item[key])))
    const y = 166 - ((value - min) / (max - min)) * 126
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

function shortTime(value) {
  return value ? value.slice(11, 16) : '--:--'
}

onMounted(load)
</script>

<template>
  <section class="page environment-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <span class="eyebrow">LIVE GROW ROOMS</span>
        <h1>菇房环境仪表盘墙</h1>
        <p>以最新采样判断催蕾条件，点击任意菇房展开过去 24 小时环境曲线。</p>
      </div>
      <div class="live-badge"><i></i>传感器在线 {{ rooms.length }}/{{ rooms.length }}</div>
    </div>

    <div class="room-wall">
      <article v-for="room in rooms" :key="room.room_id" class="room-card" :class="{ alarm: Number(room.over_limit) === 1 }" @click="openHistory(room)">
        <div v-if="Number(room.over_limit) === 1" class="alarm-corner">环境告警</div>
        <header>
          <div>
            <span class="room-code">{{ room.room_code }}</span>
            <h2>{{ room.species }}菇房</h2>
          </div>
          <span class="phase-chip">{{ room.status }}</span>
        </header>

        <div v-if="room.ticket_status" class="ticket-strip" :data-status="room.ticket_status">
          <b>越限处置单 ET-{{ String(room.ticket_id).padStart(4, '0') }}</b>
          <span>{{ room.ticket_status }}</span>
        </div>

        <div class="gauge-row">
          <div class="mini-gauge-wrap">
            <div class="mini-gauge" :style="gaugeStyle(room, 'temperature')">
              <div><strong>{{ Number(room.temperature).toFixed(1) }}</strong><span>℃</span></div>
            </div>
            <b>温度</b>
          </div>
          <div class="mini-gauge-wrap">
            <div class="mini-gauge" :style="gaugeStyle(room, 'humidity')">
              <div><strong>{{ Number(room.humidity).toFixed(0) }}</strong><span>%</span></div>
            </div>
            <b>湿度</b>
          </div>
          <div class="mini-gauge-wrap">
            <div class="mini-gauge" :style="gaugeStyle(room, 'co2_ppm')">
              <div><strong>{{ room.co2_ppm }}</strong><span>ppm</span></div>
            </div>
            <b>CO₂</b>
          </div>
        </div>

        <footer>
          <span>光照 {{ room.light_lux }} lx</span>
          <span>采样 {{ shortTime(room.recorded_at) }}</span>
          <button>查看曲线 →</button>
        </footer>
      </article>
    </div>

    <el-drawer v-model="drawerVisible" direction="btt" size="52%" :with-header="false" class="history-drawer">
      <div class="drawer-sheet" v-if="activeRoom">
        <div class="drawer-title">
          <div>
            <span class="eyebrow">24 HOURS TRACE</span>
            <h2>{{ activeRoom.room_code }} · {{ activeRoom.species }}环境曲线</h2>
          </div>
          <div class="series-legend">
            <span v-for="item in series" :key="item.key"><i :style="{ background: item.color }"></i>{{ item.label }}</span>
          </div>
        </div>
        <div class="line-chart">
          <svg viewBox="0 0 720 190" preserveAspectRatio="none" aria-label="24小时环境曲线">
            <line v-for="y in [40,82,124,166]" :key="y" x1="30" :y1="y" x2="690" :y2="y" class="grid-line" />
            <polyline v-for="item in series" :key="item.key" :points="item.points" :stroke="item.color" fill="none" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <div class="chart-axis"><span>{{ shortTime(history[0]?.recordedAt) }}</span><span>过去 12 小时</span><span>{{ shortTime(history[history.length - 1]?.recordedAt) }}</span></div>
        </div>
        <div class="trace-strip">
          <div v-for="item in history.slice(-8)" :key="item.id">
            <span>{{ shortTime(item.recordedAt) }}</span>
            <b>{{ item.temperature }}℃</b>
            <em>{{ item.humidity }}%</em>
            <i>{{ item.co2Ppm }}ppm</i>
          </div>
        </div>
      </div>
    </el-drawer>
  </section>
</template>
