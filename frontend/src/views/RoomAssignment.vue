<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { farmApi } from '../api'

const bags = ref([])
const rooms = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const activeBag = ref(null)
const form = reactive({ roomId: null, quantity: 1, plannedEntryDate: '', manager: '' })

// 只有已开袋、仍有剩余可用量的菌包才能新建分配；未开袋/污染/报废只读展示原因
const assignableBags = computed(() =>
  bags.value.filter(bag => bag.status === '已开袋' && Number(bag.availableQuantity) > 0))
const frozenBags = computed(() =>
  bags.value.filter(bag => !(bag.status === '已开袋' && Number(bag.availableQuantity) > 0)))

const totals = computed(() => bags.value.reduce((acc, bag) => {
  acc.total += Number(bag.totalQuantity) || 0
  acc.allocated += Number(bag.enteredQuantity) || 0
  acc.available += Number(bag.availableQuantity) || 0
  return acc
}, { total: 0, allocated: 0, available: 0 }))

function freezeReason(bag) {
  if (bag.status === '污染') return '菌包已污染，不能入房'
  if (bag.status === '报废') return '菌包已报废，不能入房'
  if (bag.status === '养菌') return '菌包仍在养菌，满袋开袋后才能入房'
  if (bag.status === '满袋') return '菌包刚满袋，需先开袋才能分批入房'
  if (bag.status === '已开袋' && Number(bag.availableQuantity) === 0) return '剩余可用量为 0，已全部分配'
  return '当前状态不参与入房'
}

async function load() {
  loading.value = true
  try {
    const [assignmentData, roomData] = await Promise.all([farmApi.getRoomAssignments(), farmApi.getRooms()])
    bags.value = assignmentData.bags || []
    rooms.value = roomData || []
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

function openAssign(bag) {
  activeBag.value = bag
  form.roomId = rooms.value[0]?.id || null
  form.quantity = Number(bag.availableQuantity)
  form.plannedEntryDate = new Date().toISOString().slice(0, 10)
  form.manager = ''
  dialogVisible.value = true
}

async function submitAssign() {
  if (!form.roomId) return ElMessage.warning('请选择菇房')
  if (!form.quantity || form.quantity <= 0) return ElMessage.warning('入房数量必须大于 0')
  if (!form.plannedEntryDate) return ElMessage.warning('请选择计划入房日')
  if (!form.manager.trim()) return ElMessage.warning('请填写负责人')
  saving.value = true
  try {
    await farmApi.assignRoomEntry(activeBag.value.id, { ...form, manager: form.manager.trim() })
    ElMessage.success('入房已登记，对应出菇批次已建立')
    dialogVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

async function openBag(bag) {
  try {
    await ElMessageBox.confirm(`${bag.batchNo} 已满袋，确认开袋？开袋后即可分批入房。`, '菌包开袋', { type: 'info' })
    await farmApi.openBag(bag.id)
    ElMessage.success('菌包已开袋')
    await load()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || error)
  }
}

async function withdraw(entry) {
  if (!entry.withdrawable) {
    ElMessage.warning(entry.withdrawReason || '该入房记录不能撤回')
    return
  }
  let operator = ''
  try {
    const result = await ElMessageBox.prompt(
      `撤回后将删除出菇批次 ${entry.fruiting_batch_no}，并向菌包归还 ${entry.quantity} 包。请填写撤回操作人。`,
      `撤回入房 ${entry.entry_no}`,
      { confirmButtonText: '确认撤回', cancelButtonText: '取消', inputPlaceholder: '操作人', inputValidator: v => (v && v.trim()) || '操作人不能为空' }
    )
    operator = result.value.trim()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || error)
    return
  }
  try {
    await farmApi.withdrawRoomEntry(entry.id, { operator })
    ElMessage.success('已撤回入房，菌包数量已归还')
    await load()
  } catch (error) { ElMessage.error(error.message) }
}

function progressStyle(bag) {
  const pct = bag.totalQuantity ? Number(bag.enteredQuantity) / Number(bag.totalQuantity) * 100 : 0
  return { width: `${Math.min(100, pct)}%` }
}

onMounted(load)
</script>

<template>
  <section class="page assignment-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <span class="eyebrow">BATCH ENTRY ROUTING</span>
        <h1>菌包分批入房</h1>
        <p>已开袋菌包可整批一次投产，也可按数量拆成多次入房；每次登记菇房、计划入房日与负责人，并自动建立出菇批次。</p>
      </div>
      <el-button class="live-badge" :loading="loading" @click="load"><i></i>刷新对账</el-button>
    </div>

    <div class="status-ribbon allocation-summary">
      <div><i class="dot olive"></i><span>原始总量</span><b>{{ totals.total }} 包</b></div>
      <div><i class="dot green"></i><span>已分配入房</span><b>{{ totals.allocated }} 包</b></div>
      <div><i class="dot gold"></i><span>剩余可用</span><b>{{ totals.available }} 包</b></div>
    </div>

    <div class="bag-list">
      <article v-for="bag in assignableBags" :key="bag.id" class="bag-card open">
        <header class="bag-card-head">
          <div class="bag-title">
            <span class="room-code">{{ bag.batchNo }}</span>
            <h2>{{ bag.formula }}</h2>
            <small>接种 {{ bag.inoculationDate }} · 满袋 {{ bag.fullDate || '—' }}</small>
          </div>
          <div class="bag-head-actions">
            <el-tag type="warning" effect="dark" round>已开袋</el-tag>
            <el-button type="primary" round @click="openAssign(bag)">分批入房</el-button>
          </div>
        </header>

        <div class="quantity-row">
          <div class="qty-block"><span>原始总量</span><strong>{{ bag.totalQuantity }}</strong><em>包</em></div>
          <div class="qty-block"><span>已分配入房</span><strong class="used">{{ bag.enteredQuantity }}</strong><em>包</em></div>
          <div class="qty-block"><span>培养架占用</span><strong class="shelf">{{ bag.shelvedQuantity }}</strong><em>包</em></div>
          <div class="qty-block"><span>剩余可用</span><strong class="free">{{ bag.availableQuantity }}</strong><em>包</em></div>
        </div>
        <div class="alloc-track">
          <i :style="progressStyle(bag)"></i>
          <em v-if="!bag.consistent" class="consistency-warn">数量与去向对账异常，请联系管理员核对</em>
        </div>

        <div class="destination-list">
          <div class="destination-caption">去向 · 各菇房出菇批次</div>
          <p v-if="!bag.entries.length && !bag.legacyBatches.length" class="empty-destination">尚未分配，可整批投产或拆成多次入房。</p>
          <div v-for="entry in bag.entries" :key="entry.id" class="destination-row" :class="{ withdrawn: entry.status === '已撤回' }">
            <div class="destination-main">
              <strong>{{ entry.fruiting_batch_no || '—' }}</strong>
              <span>{{ entry.room_code }} · {{ entry.planned_entry_date }} 入房 · 负责人 {{ entry.manager }}</span>
            </div>
            <div class="destination-meta">
              <b>{{ entry.quantity }} 包</b>
              <el-tag v-if="entry.status === '在制'" size="small" type="success">{{ entry.fruiting_status || '待采收' }}</el-tag>
              <el-tag v-else size="small" type="info">已撤回 · 数量已归还</el-tag>
              <el-button
                v-if="entry.status === '在制'"
                size="small"
                :type="entry.withdrawable ? 'danger' : 'info'"
                plain
                round
                @click="withdraw(entry)"
              >{{ entry.withdrawable ? '撤回' : '不可撤回' }}</el-button>
              <small v-if="entry.status === '已撤回'">撤回人 {{ entry.withdrawn_by || '—' }}</small>
            </div>
            <p v-if="entry.status === '在制' && !entry.withdrawable" class="block-reason">{{ entry.withdrawReason }}</p>
          </div>
          <div v-for="legacy in bag.legacyBatches" :key="`legacy-${legacy.id}`" class="destination-row legacy">
            <div class="destination-main">
              <strong>{{ legacy.batch_no }}</strong>
              <span>{{ legacy.room_code }} · {{ legacy.entry_date }} 入房</span>
            </div>
            <div class="destination-meta">
              <el-tag size="small" type="info">历史批次</el-tag>
              <el-tag size="small" type="info">{{ legacy.status }}</el-tag>
            </div>
          </div>
        </div>
      </article>

      <article v-for="bag in frozenBags" :key="bag.id" class="bag-card frozen">
        <header class="bag-card-head">
          <div class="bag-title">
            <span class="room-code">{{ bag.batchNo }}</span>
            <h2>{{ bag.formula }}</h2>
            <small>接种 {{ bag.inoculationDate }} · 满袋 {{ bag.fullDate || '—' }}</small>
          </div>
          <div class="bag-head-actions">
            <el-tag round type="info">{{ bag.status }}</el-tag>
            <el-button v-if="bag.status === '满袋'" round @click="openBag(bag)">开袋</el-button>
          </div>
        </header>
        <div class="quantity-row">
          <div class="qty-block"><span>原始总量</span><strong>{{ bag.totalQuantity }}</strong><em>包</em></div>
          <div class="qty-block"><span>已分配入房</span><strong class="used">{{ bag.enteredQuantity }}</strong><em>包</em></div>
          <div class="qty-block"><span>培养架占用</span><strong class="shelf">{{ bag.shelvedQuantity }}</strong><em>包</em></div>
          <div class="qty-block"><span>剩余可用</span><strong class="free">{{ bag.availableQuantity }}</strong><em>包</em></div>
        </div>
        <p class="freeze-note">{{ freezeReason(bag) }}</p>
      </article>
    </div>

    <el-dialog v-model="dialogVisible" width="520px" :title="`分批入房 · ${activeBag?.batchNo || ''}`" class="farm-dialog">
      <div class="position-ticket">
        <span>剩余可用 {{ activeBag?.availableQuantity }} 包</span>
        <strong>可整批投产，也可只拆入一部分</strong>
      </div>
      <el-form label-position="top">
        <el-form-item label="入房菇房">
          <el-select v-model="form.roomId" style="width:100%" placeholder="选择菇房">
            <el-option v-for="room in rooms" :key="room.id" :value="room.id"
              :label="`${room.room_code} · ${room.species} · ${room.status}`" />
          </el-select>
        </el-form-item>
        <div class="two-inputs">
          <el-form-item label="入房数量（包）">
            <el-input-number v-model="form.quantity" :min="1" :max="Number(activeBag?.availableQuantity) || 1" style="width:100%" />
          </el-form-item>
          <el-form-item label="计划入房日">
            <el-date-picker v-model="form.plannedEntryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
        </div>
        <el-form-item label="负责人">
          <el-input v-model="form.manager" placeholder="输入本次入房负责人" maxlength="40" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAssign">确认入房并建批次</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.allocation-summary { margin-bottom: 22px; }
.allocation-summary .dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; margin-right: 8px; }
.dot.olive { background: #827717; }
.dot.green { background: #5e8c61; }
.dot.gold { background: #c2a23c; }

.bag-list { display: flex; flex-direction: column; gap: 18px; }
.bag-card {
  background: var(--panel); border: 1px solid var(--line); border-radius: 4px 18px 4px 18px;
  box-shadow: 0 9px 28px rgba(48,51,36,.07); padding: 22px 24px;
}
.bag-card.frozen { opacity: .72; background: #f7f6ef; }
.bag-card-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; }
.bag-title h2 { margin: 5px 0 4px; font-size: 19px; }
.bag-title small { color: var(--muted); font-size: 12px; }
.bag-head-actions { display: flex; align-items: center; gap: 10px; }

.quantity-row { display: flex; gap: 34px; margin: 18px 0 10px; }
.qty-block span { display: block; color: var(--muted); font-size: 11px; letter-spacing: .12em; }
.qty-block strong { font-size: 26px; font-weight: 800; margin-right: 4px; }
.qty-block em { color: var(--muted); font-style: normal; font-size: 12px; }
.qty-block strong.used { color: #5e8c61; }
.qty-block strong.shelf { color: #b38a2e; }
.qty-block strong.free { color: #827717; }

.alloc-track { position: relative; height: 8px; border-radius: 99px; background: #e9e8da; overflow: visible; }
.alloc-track i { display: block; height: 100%; border-radius: 99px; background: linear-gradient(90deg, #827717, #b3a842); transition: width .3s; }
.consistency-warn { position: absolute; left: 0; top: 13px; color: var(--danger); font-size: 11px; font-style: normal; }

.destination-list { margin-top: 20px; border-top: 1px dashed var(--line); padding-top: 14px; }
.destination-caption { color: var(--olive); font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 10px; }
.empty-destination { color: var(--muted); font-size: 13px; margin: 0; }
.destination-row {
  display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
  padding: 11px 14px; border: 1px solid #e5e4d5; border-radius: 10px; background: #fffefa; margin-bottom: 8px;
}
.destination-row.withdrawn { background: #f3f2ec; opacity: .85; }
.destination-row.legacy { border-style: dashed; background: #faf9f2; }
.destination-main { display: flex; flex-direction: column; gap: 3px; }
.destination-main strong { font-size: 14px; }
.destination-main span { color: var(--muted); font-size: 12px; }
.destination-meta { display: flex; align-items: center; gap: 10px; }
.destination-meta b { font-size: 15px; color: #5e8c61; }
.destination-meta small { color: var(--muted); font-size: 11px; }
.block-reason { flex-basis: 100%; margin: 2px 0 0; color: var(--danger); font-size: 12px; }

.freeze-note { margin: 12px 0 0; color: var(--muted); font-size: 13px; }
.position-ticket {
  display: flex; justify-content: space-between; gap: 14px; align-items: center;
  margin-bottom: 16px; padding: 12px 16px; background: var(--el-color-primary-light-9);
  border-radius: 10px; font-size: 13px;
}
.position-ticket span { color: var(--olive); font-weight: 800; }
.position-ticket strong { color: var(--muted); font-weight: 600; }
.two-inputs { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
</style>
