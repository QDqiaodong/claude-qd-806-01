<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const bags = ref([])
const rooms = ref([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const activeBag = ref(null)
const requestKey = ref('')
const form = reactive({ roomId: null, quantity: 1, plannedEntryDate: '', manager: '' })

const revokeVisible = ref(false)
const revokeSaving = ref(false)
const activeAllocation = ref(null)
const revokeForm = reactive({ operator: '', note: '' })

const openedBags = computed(() => bags.value.filter(bag => bag.status === '已开袋'))
const otherBags = computed(() => bags.value.filter(bag => bag.status !== '已开袋'))
const stats = computed(() => ({
  opened: openedBags.value.length,
  remaining: openedBags.value.reduce((sum, bag) => sum + Number(bag.availableQuantity || 0), 0),
  allocated: openedBags.value.reduce((sum, bag) => sum + Number(bag.allocatedQuantity || 0), 0),
  records: openedBags.value.reduce((sum, bag) => sum + bag.allocations.filter(item => item.status === '已入房').length, 0)
}))

async function load() {
  loading.value = true
  try {
    const [board, roomList] = await Promise.all([farmApi.getAllocationBoard(), farmApi.getRooms()])
    bags.value = board.bags
    rooms.value = roomList
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

function newRequestKey() {
  return window.crypto?.randomUUID
    ? window.crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function openDialog(bag, whole) {
  activeBag.value = bag
  form.roomId = rooms.value[0]?.id || null
  form.quantity = whole ? Number(bag.availableQuantity) : Math.min(100, Number(bag.availableQuantity)) || 1
  form.plannedEntryDate = new Date().toISOString().slice(0, 10)
  form.manager = ''
  // 每次打开表单生成一次请求标识：重复点击/网络重试都按同一单处理，不会生成重复批次
  requestKey.value = newRequestKey()
  dialogVisible.value = true
}

async function submit() {
  if (!form.roomId) return ElMessage.warning('请选择入房菇房')
  if (!form.plannedEntryDate) return ElMessage.warning('请选择计划入房日')
  if (!form.manager.trim()) return ElMessage.warning('请填写负责人')
  if (!form.quantity || form.quantity < 1) return ElMessage.warning('分配数量必须大于 0')
  if (form.quantity > Number(activeBag.value.availableQuantity)) {
    return ElMessage.warning(`超出剩余可用量（当前 ${activeBag.value.availableQuantity} 包）`)
  }
  saving.value = true
  try {
    const allocation = await farmApi.createAllocation({
      requestKey: requestKey.value,
      bagBatchId: activeBag.value.id,
      roomId: form.roomId,
      quantity: form.quantity,
      plannedEntryDate: form.plannedEntryDate,
      manager: form.manager.trim()
    })
    ElMessage.success(`入房记录已建立，出菇批次 ${allocation.fruitingBatchNo} 已同步生成`)
    dialogVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

function openRevoke(bag, allocation) {
  activeBag.value = bag
  activeAllocation.value = allocation
  revokeForm.operator = ''
  revokeForm.note = ''
  revokeVisible.value = true
}

async function submitRevoke() {
  if (!revokeForm.operator.trim()) return ElMessage.warning('请填写撤回经办人')
  revokeSaving.value = true
  try {
    await farmApi.revokeAllocation(activeAllocation.value.id, {
      operator: revokeForm.operator.trim(),
      note: revokeForm.note.trim() || null
    })
    ElMessage.success('分配已撤回，数量已归还菌包批次')
    revokeVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { revokeSaving.value = false }
}

async function openBagBatch(bag) {
  try {
    await farmApi.openBag(bag.id)
    ElMessage.success(`${bag.batchNo} 已开袋，可分配入房`)
    await load()
  } catch (error) { ElMessage.error(error.message) }
}

function allocatedPercent(bag) {
  const total = Number(bag.totalQuantity || 0)
  if (!total) return 0
  return Math.min(100, Math.round(Number(bag.allocatedQuantity || 0) / total * 100))
}

function notEligibleNote(bag) {
  return {
    养菌: '养菌中，满袋并开袋后才能分配入房',
    满袋: '已满袋，开袋后即可分配入房',
    污染: '已污染，不能参与入房分配',
    报废: '已报废，不能参与入房分配'
  }[bag.status] || '当前状态不能参与入房分配'
}

onMounted(load)
</script>

<template>
  <section class="page allocation-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <span class="eyebrow">BAG-TO-ROOM ALLOCATION</span>
        <h1>菌包入房分配</h1>
        <p>已开袋菌包可整批投产或拆成多次入房；每次入房同步建立出菇批次，数量实时扣减、刷新一致。</p>
      </div>
      <div class="allocation-stats">
        <div><b>{{ stats.opened }}</b><span>已开袋批次</span></div>
        <div><b>{{ stats.remaining }}</b><span>剩余可分配</span></div>
        <div><b>{{ stats.allocated }}</b><span>已分配在房</span></div>
        <div><b>{{ stats.records }}</b><span>入房记录</span></div>
      </div>
    </div>

    <div v-if="openedBags.length" class="bag-card-grid">
      <div v-for="bag in openedBags" :key="bag.id" class="alloc-card">
        <header>
          <div>
            <span class="ticket-no">{{ bag.batchNo }}</span>
            <h2>{{ bag.formula }}</h2>
          </div>
          <em class="phase-chip">{{ bag.status }}</em>
        </header>

        <div class="alloc-metrics">
          <div><span>原始总量</span><strong>{{ bag.totalQuantity }}</strong></div>
          <div><span>已分配量</span><strong>{{ bag.allocatedQuantity }}</strong></div>
          <div class="metric-remaining"><span>剩余可用量</span><strong>{{ bag.availableQuantity }}</strong></div>
        </div>
        <div class="alloc-progress">
          <div class="alloc-progress-track"><i :style="{ width: allocatedPercent(bag) + '%' }"></i></div>
          <span>已分配 {{ allocatedPercent(bag) }}%</span>
        </div>

        <div class="alloc-records">
          <div class="alloc-records-title">
            <span>入房去向</span>
            <small>{{ bag.allocations.length ? `${bag.allocations.length} 条记录` : '暂无入房记录' }}</small>
          </div>
          <div
            v-for="record in bag.allocations"
            :key="record.id"
            class="alloc-record"
            :class="{ revoked: record.status === '已撤回' }"
          >
            <div class="alloc-record-main">
              <div>
                <b>{{ record.roomCode }}</b>
                <span class="alloc-no">{{ record.allocationNo }}</span>
              </div>
              <div class="alloc-record-sub">
                <span>{{ record.quantity }} 包</span>
                <span>计划 {{ record.plannedEntryDate }} 入房</span>
                <span>负责人 {{ record.manager }}</span>
                <span v-if="record.status === '已入房'">出菇批次 {{ record.fruitingBatchNo }} · {{ record.fruitingStatus }}</span>
                <span v-else>原出菇批次 {{ record.fruitingBatchNo }} 已撤销</span>
              </div>
              <div v-if="record.status === '已撤回'" class="revoke-info">
                已于 {{ record.revokedAt }} 由 {{ record.revokedBy }} 撤回，数量已归还<span v-if="record.revokeNote">（{{ record.revokeNote }}）</span>
              </div>
              <div v-else-if="!record.revocable" class="block-info">不可撤回：{{ record.blockReason }}</div>
            </div>
            <div class="alloc-record-side">
              <template v-if="record.status === '已入房'">
                <el-tag type="success" size="small">已入房</el-tag>
                <el-button v-if="record.revocable" size="small" type="danger" plain @click="openRevoke(bag, record)">撤回</el-button>
              </template>
              <el-tag v-else type="info" size="small">已撤回</el-tag>
            </div>
          </div>
        </div>

        <footer>
          <el-button type="primary" :disabled="!Number(bag.availableQuantity)" @click="openDialog(bag, true)">整批投产</el-button>
          <el-button :disabled="!Number(bag.availableQuantity)" @click="openDialog(bag, false)">分批入房</el-button>
          <span v-if="!Number(bag.availableQuantity)" class="alloc-done">已全部分配</span>
        </footer>
      </div>
    </div>
    <el-empty v-else description="暂无已开袋菌包，满袋批次开袋后即可分配入房" />

    <div v-if="otherBags.length" class="other-bags">
      <div class="other-bags-title">
        <span class="eyebrow">NOT ELIGIBLE</span>
        <h3>暂不能入房的批次</h3>
      </div>
      <div v-for="bag in otherBags" :key="bag.id" class="other-bag-row">
        <div class="other-bag-main">
          <b>{{ bag.batchNo }}</b>
          <span>{{ bag.formula }}</span>
        </div>
        <el-tag size="small" :type="bag.status === '满袋' ? 'warning' : 'info'">{{ bag.status }}</el-tag>
        <span class="other-bag-note">{{ notEligibleNote(bag) }}</span>
        <el-button v-if="bag.status === '满袋'" size="small" type="primary" plain @click="openBagBatch(bag)">开袋</el-button>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" width="520px" title="菌包入房" class="farm-dialog">
      <div class="dialog-batch">{{ activeBag?.batchNo }} <span>剩余可分配 {{ activeBag?.availableQuantity }} 包</span></div>
      <el-form label-position="top">
        <el-form-item label="入房数量（包）">
          <div class="quantity-line">
            <el-input-number v-model="form.quantity" :min="1" :max="Number(activeBag?.availableQuantity || 1)" />
            <el-button size="small" @click="form.quantity = Number(activeBag?.availableQuantity || 1)">整批</el-button>
          </div>
        </el-form-item>
        <div class="two-inputs">
          <el-form-item label="入房菇房">
            <el-select v-model="form.roomId" style="width:100%">
              <el-option v-for="room in rooms" :key="room.id" :value="room.id" :label="`${room.room_code} · ${room.species}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划入房日">
            <el-date-picker v-model="form.plannedEntryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
        </div>
        <el-form-item label="负责人">
          <el-input v-model="form.manager" placeholder="本次入房负责人" maxlength="40" />
        </el-form-item>
        <p class="dialog-hint">提交后立即扣减剩余可用量，并生成对应出菇批次（含三潮计划）；同一表单重复提交不会产生重复批次。</p>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确认入房</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="revokeVisible" width="480px" title="撤回入房分配" class="farm-dialog">
      <div class="dialog-batch">
        {{ activeAllocation?.allocationNo }} · {{ activeAllocation?.roomCode }}
        <span>{{ activeAllocation?.quantity }} 包将归还 {{ activeBag?.batchNo }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="撤回经办人">
          <el-input v-model="revokeForm.operator" placeholder="本次撤回操作人" maxlength="40" />
        </el-form-item>
        <el-form-item label="撤回备注（可选）">
          <el-input v-model="revokeForm.note" type="textarea" :rows="2" maxlength="255" placeholder="撤回原因，留空则不填" />
        </el-form-item>
        <p class="dialog-hint">仅允许撤回未产生采收、未关联环境处置单的分配；撤回后对应出菇批次删除、数量归还。</p>
      </el-form>
      <template #footer>
        <el-button @click="revokeVisible=false">取消</el-button>
        <el-button type="danger" :loading="revokeSaving" @click="submitRevoke">确认撤回</el-button>
      </template>
    </el-dialog>
  </section>
</template>
