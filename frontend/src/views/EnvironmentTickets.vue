<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const tickets = ref([])
const rooms = ref([])
const loading = ref(false)
const filter = ref('全部')

const reportVisible = ref(false)
const reportSaving = ref(false)
const reportForm = reactive({ roomId: null, temperature: 16, humidity: 88, co2Ppm: 900, lightLux: 120, recordedAt: '' })

const claimVisible = ref(false)
const claimSaving = ref(false)
const claimForm = reactive({ operator: '', expectedFinishAt: '' })

const noteVisible = ref(false)
const noteSaving = ref(false)
const noteForm = reactive({ operator: '', content: '' })

const resolveVisible = ref(false)
const resolveSaving = ref(false)
const resolveForm = reactive({ operator: '' })

const activeTicket = ref(null)

const STATUS_ORDER = ['待接单', '处理中', '恢复待确认', '已解除']
const speciesLimits = {
  金针菇: { temperature: [4, 12], humidity: [85, 95], co2: 1200 },
  杏鲍菇: { temperature: [12, 18], humidity: [85, 95], co2: 1500 },
  香菇: { temperature: [12, 20], humidity: [80, 95], co2: 1500 },
  平菇: { temperature: [15, 22], humidity: [80, 95], co2: 1800 }
}

const summary = computed(() => STATUS_ORDER.map(status => ({
  status,
  count: tickets.value.filter(ticket => ticket.status === status).length
})))
const filtered = computed(() => filter.value === '全部'
  ? tickets.value
  : tickets.value.filter(ticket => ticket.status === filter.value))
const reportRoom = computed(() => rooms.value.find(room => Number(room.id) === Number(reportForm.roomId)))
const reportLimits = computed(() => reportRoom.value ? speciesLimits[reportRoom.value.species] : null)

async function load() {
  loading.value = true
  try {
    ;[tickets.value, rooms.value] = await Promise.all([farmApi.getTickets(), farmApi.getRooms()])
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

function fmtTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}

function recoverPercent(ticket) {
  return Math.min(100, Math.round(Number(ticket.recover_count || 0) / 6 * 100))
}

// ---------- 上报读数 ----------
function openReport() {
  reportForm.roomId = rooms.value[0]?.id || null
  reportForm.recordedAt = ''
  fillReading(false)
  reportVisible.value = true
}

function fillReading(over) {
  const limits = reportLimits.value || { temperature: [15, 22], humidity: [80, 95], co2: 1800 }
  if (over) {
    reportForm.temperature = limits.temperature[1] + 3
    reportForm.humidity = limits.humidity[0] - 6
    reportForm.co2Ppm = limits.co2 + 400
  } else {
    reportForm.temperature = Number(((limits.temperature[0] + limits.temperature[1]) / 2).toFixed(1))
    reportForm.humidity = Math.round((limits.humidity[0] + limits.humidity[1]) / 2)
    reportForm.co2Ppm = Math.round(limits.co2 * 0.7)
  }
}

async function submitReport() {
  if (!reportForm.roomId) return ElMessage.warning('请选择菇房')
  reportSaving.value = true
  try {
    await farmApi.addEnvironmentRecord({
      roomId: reportForm.roomId,
      temperature: reportForm.temperature,
      humidity: reportForm.humidity,
      co2Ppm: reportForm.co2Ppm,
      lightLux: reportForm.lightLux,
      recordedAt: reportForm.recordedAt || null
    })
    ElMessage.success('读数已入库，连续越限/恢复将自动推进处置流程')
    reportVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { reportSaving.value = false }
}

// ---------- 接单 ----------
function openClaim(ticket) {
  activeTicket.value = ticket
  claimForm.operator = ''
  claimForm.expectedFinishAt = ''
  claimVisible.value = true
}

async function submitClaim() {
  if (!claimForm.operator.trim()) return ElMessage.warning('请填写接单人')
  if (!claimForm.expectedFinishAt) return ElMessage.warning('请选择预计完成时间')
  claimSaving.value = true
  try {
    await farmApi.claimTicket(activeTicket.value.id, { ...claimForm, operator: claimForm.operator.trim() })
    ElMessage.success('已接单，处置单进入处理中')
    claimVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { claimSaving.value = false }
}

// ---------- 处理记录 ----------
function openNote(ticket) {
  activeTicket.value = ticket
  noteForm.operator = ticket.claimed_by || ''
  noteForm.content = ''
  noteVisible.value = true
}

async function submitNote() {
  if (!noteForm.operator.trim()) return ElMessage.warning('请填写经办人')
  if (!noteForm.content.trim()) return ElMessage.warning('请填写处理记录')
  noteSaving.value = true
  try {
    await farmApi.addTicketNote(activeTicket.value.id, {
      operator: noteForm.operator.trim(),
      content: noteForm.content.trim()
    })
    ElMessage.success('处理记录已写入处置时间线')
    noteVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { noteSaving.value = false }
}

// ---------- 解除 ----------
function openResolve(ticket) {
  activeTicket.value = ticket
  resolveForm.operator = ticket.claimed_by || ''
  resolveVisible.value = true
}

async function submitResolve() {
  if (!resolveForm.operator.trim()) return ElMessage.warning('请填写解除人')
  resolveSaving.value = true
  try {
    await farmApi.resolveTicket(activeTicket.value.id, { operator: resolveForm.operator.trim() })
    ElMessage.success('处置单已解除，暂停批次已恢复原状态')
    resolveVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { resolveSaving.value = false }
}

onMounted(load)
</script>

<template>
  <section class="page tickets-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <span class="eyebrow">OVER-LIMIT DISPATCH</span>
        <h1>环境越限处置闭环</h1>
        <p>连续 3 条越限自动开单并暂停在产批次；连续 6 条恢复后进入待确认，确认解除才恢复生产。</p>
      </div>
      <div class="ticket-actions">
        <el-radio-group v-model="filter">
          <el-radio-button v-for="item in ['全部', ...STATUS_ORDER]" :key="item" :value="item">{{ item }}</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="openReport">上报环境读数</el-button>
      </div>
    </div>

    <div class="ticket-ribbon">
      <div v-for="item in summary" :key="item.status" :data-status="item.status">
        <span>{{ item.status }}</span><b>{{ item.count }}</b>
      </div>
      <div class="ribbon-hint">
        <span>判定口径</span>
        <b>越限 ×3 开单 · 恢复 ×6 待确认</b>
      </div>
    </div>

    <el-empty v-if="!filtered.length" description="暂无处置单，环境运行平稳" />

    <div class="ticket-grid">
      <article v-for="ticket in filtered" :key="ticket.id" class="ticket-card" :data-status="ticket.status">
        <header>
          <div>
            <span class="ticket-no">ET-{{ String(ticket.id).padStart(4, '0') }}</span>
            <h2>{{ ticket.room_code }} · {{ ticket.species }}菇房</h2>
          </div>
          <span class="ticket-status" :data-status="ticket.status">{{ ticket.status }}</span>
        </header>

        <div class="trigger-box">
          <div class="trigger-metric">
            <b>触发指标</b>
            <span>{{ ticket.trigger_metric }}</span>
            <time>触发于 {{ fmtTime(ticket.trigger_at) }}</time>
          </div>
          <div class="trigger-readings">
            <div v-for="reading in ticket.triggerReadingList" :key="reading.id" class="reading-chip">
              <span>{{ fmtTime(reading.recordedAt) }}</span>
              <b>{{ reading.temperature }}℃ · {{ reading.humidity }}% · {{ reading.co2Ppm }}ppm</b>
            </div>
          </div>
        </div>

        <div class="ticket-stats">
          <div><span>累计越限</span><strong>{{ ticket.over_count }}<small>条</small></strong></div>
          <div class="recover-cell">
            <span>连续恢复 {{ ticket.recover_count }}/6 条</span>
            <el-progress
              :percentage="recoverPercent(ticket)"
              :stroke-width="9"
              :color="ticket.status === '恢复待确认' ? '#2f6f4f' : '#827717'"
            />
          </div>
          <div><span>暂停批次</span><strong>{{ ticket.batches.length }}<small>个</small></strong></div>
        </div>

        <div v-if="ticket.batches.length" class="batch-chips">
          <span
            v-for="batch in ticket.batches"
            :key="batch.id"
            class="batch-chip"
            :class="{ restored: Number(batch.restored) === 1 }"
          >
            {{ batch.batch_no }}
            <small>{{ Number(batch.restored) === 1 ? `已恢复为「${batch.prev_status}」` : `${batch.prev_status} → 环境暂停` }}</small>
          </span>
        </div>

        <div class="duty-line" v-if="ticket.claimed_by || ticket.resolved_by">
          <span v-if="ticket.claimed_by">接单 {{ ticket.claimed_by }} · {{ fmtTime(ticket.claimed_at) }} · 预计完成 {{ fmtTime(ticket.expected_finish_at) }}</span>
          <span v-if="ticket.resolved_by">解除 {{ ticket.resolved_by }} · {{ fmtTime(ticket.resolved_at) }}</span>
        </div>

        <div class="event-timeline">
          <div v-for="event in ticket.events" :key="event.id" class="event-item" :data-type="event.eventType">
            <i></i>
            <div>
              <b>{{ event.eventType }}<span v-if="event.actor"> · {{ event.actor }}</span></b>
              <p>{{ event.detail }}</p>
              <time>{{ fmtTime(event.createdAt) }}</time>
            </div>
          </div>
        </div>

        <footer>
          <el-button v-if="ticket.status === '待接单'" type="primary" @click="openClaim(ticket)">接单</el-button>
          <el-button v-if="ticket.status !== '已解除'" @click="openNote(ticket)">填写处理记录</el-button>
          <el-button v-if="ticket.status === '恢复待确认'" type="success" @click="openResolve(ticket)">确认解除</el-button>
          <span v-if="ticket.status === '已解除'" class="resolved-mark">已闭环</span>
        </footer>
      </article>
    </div>

    <el-dialog v-model="reportVisible" width="560px" title="上报环境读数" class="farm-dialog">
      <el-form label-position="top">
        <el-form-item label="菇房">
          <el-select v-model="reportForm.roomId" style="width:100%">
            <el-option v-for="room in rooms" :key="room.id" :value="room.id" :label="`${room.room_code} · ${room.species}`" />
          </el-select>
        </el-form-item>
        <div class="quick-fill">
          <el-button size="small" @click="fillReading(true)">填入越限示例</el-button>
          <el-button size="small" @click="fillReading(false)">填入适宜示例</el-button>
          <span v-if="reportLimits">
            适宜：温度 {{ reportLimits.temperature[0] }}~{{ reportLimits.temperature[1] }}℃ ·
            湿度 {{ reportLimits.humidity[0] }}~{{ reportLimits.humidity[1] }}% · CO₂≤{{ reportLimits.co2 }}ppm
          </span>
        </div>
        <div class="two-inputs">
          <el-form-item label="温度 ℃"><el-input-number v-model="reportForm.temperature" :precision="1" :step="0.5" /></el-form-item>
          <el-form-item label="湿度 %"><el-input-number v-model="reportForm.humidity" :precision="1" :step="1" /></el-form-item>
        </div>
        <div class="two-inputs">
          <el-form-item label="CO₂ ppm"><el-input-number v-model="reportForm.co2Ppm" :min="0" :step="50" /></el-form-item>
          <el-form-item label="光照 lux"><el-input-number v-model="reportForm.lightLux" :min="0" :step="10" /></el-form-item>
        </div>
        <el-form-item label="采集时间（留空按当前时间；早于该房最新采集时间的迟到读数只存档、不参与判定）">
          <el-date-picker v-model="reportForm.recordedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportVisible=false">取消</el-button>
        <el-button type="primary" :loading="reportSaving" @click="submitReport">保存读数</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="claimVisible" width="480px" title="值班接单" class="farm-dialog">
      <div class="dialog-batch">
        ET-{{ String(activeTicket?.id || 0).padStart(4, '0') }} · {{ activeTicket?.room_code }}
        <span>{{ activeTicket?.trigger_metric }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="接单人"><el-input v-model="claimForm.operator" placeholder="值班人员姓名" /></el-form-item>
        <el-form-item label="预计完成时间">
          <el-date-picker v-model="claimForm.expectedFinishAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimVisible=false">取消</el-button>
        <el-button type="primary" :loading="claimSaving" @click="submitClaim">确认接单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="noteVisible" width="520px" title="填写处理记录" class="farm-dialog">
      <div class="dialog-batch">
        ET-{{ String(activeTicket?.id || 0).padStart(4, '0') }} · {{ activeTicket?.room_code }}
        <span>{{ activeTicket?.status }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="经办人"><el-input v-model="noteForm.operator" placeholder="处理人姓名" /></el-form-item>
        <el-form-item label="处理记录">
          <el-input v-model="noteForm.content" type="textarea" :rows="4" placeholder="记录处置措施、现场情况与下一步安排" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="noteVisible=false">取消</el-button>
        <el-button type="primary" :loading="noteSaving" @click="submitNote">写入时间线</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveVisible" width="480px" title="确认解除处置单" class="farm-dialog">
      <div class="dialog-batch">
        ET-{{ String(activeTicket?.id || 0).padStart(4, '0') }} · {{ activeTicket?.room_code }}
        <span>连续恢复 {{ activeTicket?.recover_count }} 条</span>
      </div>
      <p class="resolve-hint">
        解除后仅恢复本处置单暂停的 {{ activeTicket?.batches?.length || 0 }} 个批次至原状态；
        原本人工暂停或已结束的批次不受影响。
      </p>
      <el-form label-position="top">
        <el-form-item label="解除人"><el-input v-model="resolveForm.operator" placeholder="确认人姓名" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible=false">取消</el-button>
        <el-button type="success" :loading="resolveSaving" @click="submitResolve">确认解除并恢复生产</el-button>
      </template>
    </el-dialog>
  </section>
</template>
