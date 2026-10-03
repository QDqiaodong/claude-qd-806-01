<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { farmApi } from '../api'

const bags = ref([])
const summary = ref([])
const loading = ref(false)
const selectMode = ref(false)
const selected = ref(new Set())
const dialogVisible = ref(false)
const activeBag = ref(null)
const saving = ref(false)
const form = reactive({ contaminationType: '绿霉', treatment: '隔离', discoveredDate: '', lossQuantity: 1, note: '' })

const cells = computed(() => bags.value.flatMap(batch => {
  const count = Math.max(8, Math.min(28, Math.ceil(Number(batch.totalQuantity) / 50)))
  return Array.from({ length: count }, (_, index) => ({
    key: `${batch.id}-${index}`,
    index: index + 1,
    batch,
    status: batch.status
  }))
}))

const selectedCount = computed(() => selected.value.size)
const statusCounts = computed(() => bags.value.reduce((acc, bag) => {
  acc[bag.status] = (acc[bag.status] || 0) + Number(bag.totalQuantity)
  return acc
}, {}))

async function load() {
  loading.value = true
  try { [bags.value, summary.value] = await Promise.all([farmApi.getBags(), farmApi.getContaminationSummary()]) }
  catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

function clickCell(cell) {
  if (selectMode.value) {
    const next = new Set(selected.value)
    next.has(cell.key) ? next.delete(cell.key) : next.add(cell.key)
    selected.value = next
    return
  }
  openForm(cell.batch, 1)
}

function openForm(batch, quantity) {
  activeBag.value = batch
  form.contaminationType = '绿霉'
  form.treatment = '隔离'
  form.discoveredDate = new Date().toISOString().slice(0, 10)
  form.lossQuantity = quantity
  form.note = ''
  dialogVisible.value = true
}

function openBatchForm() {
  if (!selected.value.size) return ElMessage.warning('请先勾选菌包格')
  const first = cells.value.find(cell => selected.value.has(cell.key))
  openForm(first.batch, selected.value.size)
}

async function submit() {
  saving.value = true
  try {
    if (selectMode.value && selected.value.size) {
      const groups = {}
      cells.value.filter(cell => selected.value.has(cell.key)).forEach(cell => {
        groups[cell.batch.id] = (groups[cell.batch.id] || 0) + 1
      })
      await Promise.all(Object.entries(groups).map(([bagBatchId, count]) => farmApi.registerContamination({
        ...form, bagBatchId: Number(bagBatchId), lossQuantity: count
      })))
    } else {
      await farmApi.registerContamination({ ...form, bagBatchId: activeBag.value.id })
    }
    ElMessage.success('污染类型与处理措施已登记')
    dialogVisible.value = false
    selected.value = new Set()
    selectMode.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

async function discard() {
  try {
    await ElMessageBox.confirm(`确认将 ${activeBag.value.batchNo} 标记为报废？`, '报废确认', { type: 'warning' })
    await farmApi.discardBag(activeBag.value.id)
    ElMessage.success('菌包批次已报废')
    dialogVisible.value = false
    await load()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || error)
  }
}

onMounted(load)
</script>

<template>
  <section class="page contamination-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <span class="eyebrow">BAG INSPECTION MATRIX</span>
        <h1>菌包污染排查网格</h1>
        <p>每个方格代表一组菌包抽检位；开启批量标记后，可跨批次勾选并统一登记。</p>
      </div>
      <div class="grid-actions">
        <el-button :type="selectMode ? 'primary' : 'default'" @click="selectMode=!selectMode; selected=new Set()">
          {{ selectMode ? '退出批量' : '批量标记' }}
        </el-button>
        <el-button v-if="selectMode" type="danger" :disabled="!selectedCount" @click="openBatchForm">处理选中 {{ selectedCount }}</el-button>
      </div>
    </div>

    <div class="status-ribbon">
      <div v-for="item in [{s:'养菌',c:'gray'},{s:'满袋',c:'green'},{s:'已开袋',c:'olive'},{s:'污染',c:'red'},{s:'报废',c:'black'}]" :key="item.s">
        <i :class="item.c"></i><span>{{ item.s }}</span><b>{{ statusCounts[item.s] || 0 }}</b>
      </div>
    </div>

    <div class="inspection-layout">
      <div class="batch-matrix">
        <div v-for="batch in bags" :key="batch.id" class="batch-band">
          <div class="batch-band-title">
            <div><strong>{{ batch.batchNo }}</strong><span>{{ batch.formula }}</span></div>
            <em>{{ batch.status }} · {{ batch.totalQuantity }} 包</em>
          </div>
          <div class="cell-grid">
            <button
              v-for="cell in cells.filter(item => item.batch.id === batch.id)"
              :key="cell.key"
              class="bag-cell"
              :class="[`state-${cell.status}`, { selected: selected.has(cell.key) }]"
              :title="`${batch.batchNo} / 抽检位 ${cell.index}`"
              @click="clickCell(cell)"
            >
              <span v-if="selected.has(cell.key)">✓</span>
              <small v-else>{{ cell.index }}</small>
            </button>
          </div>
        </div>
      </div>

      <aside class="contamination-report">
        <span class="eyebrow">LOSS BREAKDOWN</span>
        <h3>污染损失分布</h3>
        <div v-for="item in summary" :key="item.contamination_type" class="loss-row">
          <div><b>{{ item.contamination_type }}</b><span>{{ item.record_count }} 次记录</span></div>
          <strong>{{ item.total_loss }}<small>包</small></strong>
          <div class="loss-track"><i :style="{ width: `${Math.min(100, Number(item.total_loss) / 1.2)}%` }"></i></div>
        </div>
        <p>红色格需先登记处理措施，完成处理后才允许报废。</p>
      </aside>
    </div>

    <el-dialog v-model="dialogVisible" width="520px" title="污染处理登记" class="farm-dialog">
      <div class="dialog-batch">{{ activeBag?.batchNo }} <span>{{ selectMode ? `批量选中 ${selectedCount} 格` : activeBag?.status }}</span></div>
      <el-form label-position="top">
        <div class="two-inputs">
          <el-form-item label="污染类型">
            <el-select v-model="form.contaminationType"><el-option v-for="v in ['绿霉','链孢霉','细菌']" :key="v" :value="v" /></el-select>
          </el-form-item>
          <el-form-item label="处理措施">
            <el-select v-model="form.treatment"><el-option v-for="v in ['剔除','隔离','药剂']" :key="v" :value="v" /></el-select>
          </el-form-item>
        </div>
        <div class="two-inputs">
          <el-form-item label="发现日期"><el-date-picker v-model="form.discoveredDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="损失数量"><el-input-number v-model="form.lossQuantity" :min="1" /></el-form-item>
        </div>
        <el-form-item label="现场备注"><el-input v-model="form.note" type="textarea" :rows="3" placeholder="记录污染位置与扩散范围" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="!selectMode && activeBag?.status === '污染'" type="danger" plain @click="discard">完成处理并报废</el-button>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">登记处理</el-button>
      </template>
    </el-dialog>
  </section>
</template>
