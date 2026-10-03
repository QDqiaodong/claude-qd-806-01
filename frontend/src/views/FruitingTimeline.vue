<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const batches = ref([])
const selectedId = ref(null)
const flushes = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const activeFlush = ref(null)
const saving = ref(false)
const form = reactive({ harvestDate: '', gradeAYield: 0, gradeBYield: 0, gradeCYield: 0, harvestedBy: '' })

const selectedBatch = computed(() => batches.value.find(item => Number(item.id) === Number(selectedId.value)))
const remaining = computed(() => {
  if (!selectedBatch.value) return 0
  return Number(selectedBatch.value.expected_yield) - Number(selectedBatch.value.harvested_yield)
})

async function loadBatches() {
  loading.value = true
  try {
    batches.value = await farmApi.getFruitingBatches()
    if (!selectedId.value && batches.value.length) selectedId.value = batches.value[0].id
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

async function loadFlushes() {
  if (!selectedId.value) return
  try { flushes.value = await farmApi.getFlushes(selectedId.value) }
  catch (error) { ElMessage.error(error.message) }
}

function openHarvest(flush) {
  if (selectedBatch.value?.status === '环境暂停') {
    ElMessage.warning('该批次因环境越限处置暂停生产，处置单解除后才能采收')
    return
  }
  activeFlush.value = flush
  form.harvestDate = flush.harvestDate || new Date().toISOString().slice(0, 10)
  form.gradeAYield = Number(flush.gradeAYield || 0)
  form.gradeBYield = Number(flush.gradeBYield || 0)
  form.gradeCYield = Number(flush.gradeCYield || 0)
  form.harvestedBy = flush.harvestedBy || ''
  dialogVisible.value = true
}

async function submitHarvest() {
  saving.value = true
  try {
    await farmApi.harvest(selectedId.value, activeFlush.value.flushNo, { ...form })
    ElMessage.success('采收产量已写入批次与库存')
    dialogVisible.value = false
    await loadBatches()
    await loadFlushes()
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

function barHeight(flush) {
  const expected = Number(selectedBatch.value?.expected_yield || 1)
  return `${Math.max(10, Math.min(152, Number(flush.yieldKg || 0) / expected * 420))}px`
}

function flushName(no) {
  return ['一潮', '二潮', '三潮'][no - 1]
}

watch(selectedId, loadFlushes)
onMounted(loadBatches)
</script>

<template>
  <section class="page timeline-page" v-loading="loading">
    <div class="page-heading timeline-heading">
      <div>
        <span class="eyebrow">HARVEST RHYTHM</span>
        <h1>出菇潮次时间轴</h1>
        <p>沿批次生长节奏记录一潮、二潮与三潮，产量柱随采收入账即时增长。</p>
      </div>
      <el-select v-model="selectedId" class="batch-switch" placeholder="选择出菇批次">
        <el-option v-for="batch in batches" :key="batch.id" :value="batch.id" :label="`${batch.batch_no} · ${batch.species}`" />
      </el-select>
    </div>

    <div v-if="selectedBatch" class="batch-brief">
      <div><span>当前批次</span><strong>{{ selectedBatch.batch_no }}</strong></div>
      <div><span>菇房</span><strong>{{ selectedBatch.room_code }}</strong></div>
      <div><span>菌包批次</span><strong>{{ selectedBatch.bag_batch_no }}</strong></div>
      <div><span>批次状态</span><strong :class="{ 'paused-text': selectedBatch.status === '环境暂停' }">{{ selectedBatch.status }}</strong></div>
      <div><span>预计产量</span><strong>{{ selectedBatch.expected_yield }} kg</strong></div>
      <div class="remaining"><span>剩余预产量</span><strong>{{ remaining.toFixed(1) }} kg</strong></div>
    </div>
    <div v-if="selectedBatch?.status === '环境暂停'" class="pause-banner">
      环境越限处置单未解除，该批次暂停生产与采收；解除后自动恢复原状态。
    </div>

    <div class="timeline-stage" v-if="flushes.length">
      <div class="timeline-rule"></div>
      <div v-for="flush in flushes" :key="flush.id || flush.flushNo" class="flush-stop" @click="openHarvest(flush)">
        <div class="yield-column-zone">
          <span class="yield-number">{{ Number(flush.yieldKg || 0).toFixed(1) }}<small>kg</small></span>
          <div class="yield-column" :class="{ empty: !Number(flush.yieldKg) }" :style="{ height: barHeight(flush) }">
            <i></i>
          </div>
        </div>
        <button class="timeline-node" :class="{ complete: flush.status === '已采收' }">
          <span>{{ flush.flushNo }}</span>
        </button>
        <div class="flush-meta">
          <h3>{{ flushName(flush.flushNo) }}</h3>
          <span>{{ flush.harvestDate || '等待采收' }}</span>
          <b>{{ flush.harvestedBy || '点击录入' }}</b>
        </div>
      </div>
    </div>

    <div class="grade-ledger" v-if="flushes.length">
      <div v-for="flush in flushes" :key="`grade-${flush.flushNo}`">
        <span>{{ flushName(flush.flushNo) }}等级构成</span>
        <div class="grade-bar">
          <i class="grade-a" :style="{ flex: Number(flush.gradeAYield || 0) }"></i>
          <i class="grade-b" :style="{ flex: Number(flush.gradeBYield || 0) }"></i>
          <i class="grade-c" :style="{ flex: Number(flush.gradeCYield || 0) }"></i>
        </div>
        <small>A {{ flush.gradeAYield }} / B {{ flush.gradeBYield }} / C {{ flush.gradeCYield }} kg</small>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" width="520px" :title="`${flushName(activeFlush?.flushNo || 1)}采收录入`" class="farm-dialog">
      <el-form label-position="top">
        <el-form-item label="采收日期"><el-date-picker v-model="form.harvestDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <div class="three-inputs">
          <el-form-item label="A级产量 kg"><el-input-number v-model="form.gradeAYield" :min="0" :precision="1" /></el-form-item>
          <el-form-item label="B级产量 kg"><el-input-number v-model="form.gradeBYield" :min="0" :precision="1" /></el-form-item>
          <el-form-item label="C级产量 kg"><el-input-number v-model="form.gradeCYield" :min="0" :precision="1" /></el-form-item>
        </div>
        <el-form-item label="采收人"><el-input v-model="form.harvestedBy" placeholder="输入采收负责人" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitHarvest">确认入账</el-button>
      </template>
    </el-dialog>
  </section>
</template>
