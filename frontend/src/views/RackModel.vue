<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const rooms = ref([])
const roomId = ref(null)
const racks = ref([])
const rackId = ref(null)
const bags = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const activeCell = ref(null)
const saving = ref(false)
const form = reactive({ bagBatchId: null, quantity: 20 })

const activeRack = computed(() => racks.value.find(item => Number(item.id) === Number(rackId.value)))
const activeRoom = computed(() => rooms.value.find(item => Number(item.id) === Number(roomId.value)))
const availableBags = computed(() => bags.value.filter(item => !['污染', '报废'].includes(item.status) && Number(item.availableQuantity) > 0))

async function loadBase() {
  loading.value = true
  try {
    ;[rooms.value, bags.value] = await Promise.all([farmApi.getRooms(), farmApi.getBags()])
    if (rooms.value.length) roomId.value = rooms.value[0].id
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

async function loadRacks() {
  if (!roomId.value) return
  try {
    racks.value = await farmApi.getRacks(roomId.value)
    rackId.value = racks.value[0]?.id || null
  } catch (error) { ElMessage.error(error.message) }
}

function levels(rack) {
  return Array.from({ length: rack.levels }, (_, index) => rack.levels - index)
}

function positions(rack) {
  return Array.from({ length: rack.slotsPerLevel }, (_, index) => index + 1)
}

function slotAt(levelNo, slotNo) {
  return activeRack.value?.slots.find(slot => Number(slot.level_no) === levelNo && Number(slot.slot_no) === slotNo)
}

function rateAt(levelNo) {
  if (!activeRack.value) return 0
  const used = activeRack.value.slots.filter(slot => Number(slot.level_no) === levelNo).length
  return Math.round(used / activeRack.value.slotsPerLevel * 100)
}

function openShelf(levelNo, slotNo) {
  if (slotAt(levelNo, slotNo)) return
  activeCell.value = { levelNo, slotNo }
  form.bagBatchId = availableBags.value[0]?.id || null
  form.quantity = 20
  dialogVisible.value = true
}

async function submitShelf() {
  saving.value = true
  try {
    await farmApi.shelfBag(rackId.value, { ...form, ...activeCell.value })
    ElMessage.success('菌包已放入指定层架格位')
    dialogVisible.value = false
    await Promise.all([loadRacks(), farmApi.getBags().then(data => { bags.value = data })])
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

watch(roomId, loadRacks)
onMounted(loadBase)
</script>

<template>
  <section class="page rack-page" v-loading="loading">
    <div class="page-heading rack-heading">
      <div>
        <span class="eyebrow">SHELF OCCUPANCY MODEL</span>
        <h1>培养架层架立体图</h1>
        <p>按真实层号与格位查看菌包占位，点击空格即可完成指定批次上架。</p>
      </div>
      <div class="rack-filters">
        <el-select v-model="roomId" placeholder="选择菇房">
          <el-option v-for="room in rooms" :key="room.id" :value="room.id" :label="`${room.room_code} · ${room.species}`" />
        </el-select>
        <el-select v-model="rackId" placeholder="选择培养架">
          <el-option v-for="rack in racks" :key="rack.id" :value="rack.id" :label="rack.rackCode" />
        </el-select>
      </div>
    </div>

    <div v-if="activeRack" class="rack-workbench">
      <div class="rack-blueprint">
        <div class="rack-caption">
          <div><span>{{ activeRoom?.room_code }}</span><strong>{{ activeRack.rackCode }}</strong></div>
          <p>{{ activeRack.levels }} 层 × 每层 {{ activeRack.slotsPerLevel }} 格</p>
        </div>
        <div class="rack-frame">
          <div v-for="level in levels(activeRack)" :key="level" class="shelf-level">
            <div class="level-label"><small>LEVEL</small><b>{{ String(level).padStart(2, '0') }}</b></div>
            <div class="shelf-grid" :style="{ gridTemplateColumns: `repeat(${activeRack.slotsPerLevel}, 1fr)` }">
              <button
                v-for="position in positions(activeRack)"
                :key="position"
                class="rack-cell"
                :class="{ occupied: slotAt(level, position), contaminated: slotAt(level, position)?.bag_status === '污染' }"
                @click="openShelf(level, position)"
              >
                <template v-if="slotAt(level, position)">
                  <span>{{ slotAt(level, position).batch_no }}</span>
                  <b>{{ slotAt(level, position).quantity }} 包</b>
                </template>
                <template v-else>
                  <span class="empty-mark">+</span>
                  <small>{{ position }}号空位</small>
                </template>
              </button>
            </div>
            <div class="level-rate">
              <el-progress type="circle" :percentage="rateAt(level)" :width="54" :stroke-width="6" color="#827717" />
              <span>上架率</span>
            </div>
          </div>
          <div class="rack-foot"><i></i><i></i><span>{{ activeRack.rackCode }} / 承重结构示意</span><i></i><i></i></div>
        </div>
      </div>

      <aside class="rack-note">
        <span class="eyebrow">POSITION GUIDE</span>
        <h3>上架规则</h3>
        <ol>
          <li><b>01</b><span>污染与报废批次不可上架</span></li>
          <li><b>02</b><span>单格绑定一个菌包批次</span></li>
          <li><b>03</b><span>格位占用后不可重复放入</span></li>
        </ol>
        <div class="rack-summary"><span>当前占用</span><strong>{{ activeRack.slots.length }}</strong><small>/ {{ activeRack.levels * activeRack.slotsPerLevel }} 格</small></div>
      </aside>
    </div>

    <el-dialog v-model="dialogVisible" width="480px" title="空格位上架" class="farm-dialog">
      <div class="position-ticket"><span>{{ activeRack?.rackCode }}</span><strong>{{ activeCell?.levelNo }} 层 · {{ activeCell?.slotNo }} 号格</strong></div>
      <el-form label-position="top">
        <el-form-item label="菌包批次">
          <el-select v-model="form.bagBatchId" style="width:100%">
            <el-option v-for="bag in availableBags" :key="bag.id" :value="bag.id" :label="`${bag.batchNo} · 可用 ${bag.availableQuantity} 包`" />
          </el-select>
        </el-form-item>
        <el-form-item label="上架数量"><el-input-number v-model="form.quantity" :min="1" :max="200" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitShelf">确认上架</el-button>
      </template>
    </el-dialog>
  </section>
</template>
