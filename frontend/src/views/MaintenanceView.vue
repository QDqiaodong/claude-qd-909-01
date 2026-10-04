<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const windows = ref([])
const segments = ref([])
const loading = ref(false)
const segmentFilter = ref(null)
const dialog = ref(false)
const editingId = ref(null)
const form = ref({})

function nextWindowNo() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `MW-${d.getFullYear()}-${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function nowLocal(offsetHours) {
  const d = new Date(Date.now() + offsetHours * 3600 * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

// 后端给的是「YYYY-MM-DD HH:mm:ss」，表单 datetime 控件要 ISO「YYYY-MM-DDTHH:mm」
function toPicker(value) {
  return value ? value.replace(' ', 'T').slice(0, 16) : ''
}

function openCreate() {
  editingId.value = null
  form.value = {
    windowNo: nextWindowNo(),
    canalSegmentId: segmentFilter.value || segments.value[0]?.id || null,
    startAt: nowLocal(24),
    endAt: nowLocal(56),
    reason: '',
    scope: '全段停水',
    note: ''
  }
  dialog.value = true
}

function openEdit(w) {
  editingId.value = w.id
  form.value = {
    windowNo: w.windowNo,
    canalSegmentId: w.canalSegmentId,
    startAt: toPicker(w.startAt),
    endAt: toPicker(w.endAt),
    reason: w.reason,
    scope: w.scope,
    note: w.note
  }
  dialog.value = true
}

async function submit() {
  loading.value = true
  try {
    if (editingId.value) {
      await api.maintenance.update(editingId.value, form.value)
      ElMessage.success('停水窗口已变更，重叠项已重扫')
    } else {
      await api.maintenance.create(form.value)
      ElMessage.success('停水窗口已登记，重叠计划与配水已标为待避让')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function revoke(w) {
  let note = ''
  try {
    const res = await ElMessageBox.prompt(
      `撤销窗口 ${w.windowNo} 后，尚未人工确认的待避让项会自动恢复；已顺延 / 已拆分 / 例外保留的结果与历史不覆盖。可填撤销说明：`,
      '撤销停水窗口',
      { inputPlaceholder: '如：检修提前完成，恢复通水', confirmButtonText: '确认撤销', cancelButtonText: '再想想' }
    )
    note = res.value || ''
  } catch {
    return
  }
  loading.value = true
  try {
    await api.maintenance.revoke(w.id, note)
    ElMessage.success('停水窗口已撤销')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function shift(imp) {
  try {
    await ElMessageBox.confirm(
      `整体顺延「${imp.targetLabel}」：计划周期 / 配水条 / 相关用水申请的供水日期一起后移出窗口，剩余可配水量按新日期重算。`,
      '整体顺延',
      { confirmButtonText: '顺延', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  loading.value = true
  try {
    await api.maintenance.shift(imp.id, '灌区调度')
    ElMessage.success('已整体顺延')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function split(imp) {
  try {
    await ElMessageBox.confirm(
      `把「${imp.targetLabel}」落在窗口内的配水按当日剩余容量拆分到窗口外日期，原配水条取消保留历史。`,
      '拆分到窗口外日期',
      { confirmButtonText: '拆分', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  loading.value = true
  try {
    await api.maintenance.split(imp.id, '灌区调度')
    ElMessage.success('已拆分到窗口外日期')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function keep(imp) {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      `保留「${imp.targetLabel}」原安排不变，必须填写例外依据：`,
      '例外保留',
      {
        inputPlaceholder: '如：作物需水临界期，经灌区调度批准照常供水',
        inputValidator: (v) => (v && String(v).trim().length > 0) || '例外依据不能为空',
        confirmButtonText: '保留',
        cancelButtonText: '取消'
      }
    )
    reason = res.value
  } catch {
    return
  }
  loading.value = true
  try {
    await api.maintenance.keep(imp.id, reason, '灌区调度')
    ElMessage.success('已例外保留')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const [ws, segs] = await Promise.all([
      api.maintenance.list(segmentFilter.value || undefined),
      api.canal.segments()
    ])
    windows.value = ws || []
    segments.value = segs || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function pickSegment(id) {
  segmentFilter.value = segmentFilter.value === id ? null : id
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">停水窗口 · 渠段检修与避让处置</span>
      <span class="view-tip">窗口生效 / 变更即重扫重叠的计划与配水条；撤销只恢复未人工确认的待避让项</span>
    </div>

    <div class="chip-row">
      <span class="chip" :class="{ on: segmentFilter === null }" @click="segmentFilter = null; load()">全部渠段</span>
      <span
        v-for="s in segments"
        :key="s.id"
        class="chip"
        :class="{ on: segmentFilter === s.id }"
        @click="pickSegment(s.id)"
      >{{ s.segmentCode }}</span>
      <el-button size="small" type="primary" style="margin-left:auto" @click="openCreate">登记停水窗口</el-button>
    </div>

    <div v-if="!windows.length" class="panel"><div class="empty-tip">暂无停水窗口</div></div>

    <div v-for="w in windows" :key="w.id" class="panel mw-card" :class="{ revoked: w.status === 'REVOKED' }">
      <div class="mw-head">
        <b class="mw-no">{{ w.windowNo }}</b>
        <span class="tag-line" :style="{ color: w.statusColor }">
          <i class="dot" :style="{ background: w.statusColor }"></i>{{ w.statusName }}
        </span>
        <span class="mw-seg">{{ w.segmentCode }} · {{ w.segmentName }}</span>
        <span class="mw-count">待避让 {{ w.pendingCount }} / 冲击 {{ w.impactCount }}</span>
        <span class="mw-actions">
          <template v-if="w.status === 'ACTIVE'">
            <el-button size="small" plain @click="openEdit(w)">变更</el-button>
            <el-button size="small" type="danger" plain @click="revoke(w)">撤销</el-button>
          </template>
          <span v-else class="mw-revoked-at">撤销于 {{ w.revokedAt || '—' }}</span>
        </span>
      </div>
      <div class="mw-meta">
        <span>停水时间：<b>{{ w.startAt }}</b> ~ <b>{{ w.endAt }}</b></span>
        <span>范围：{{ w.scope || '全段停水' }}</span>
        <span>登记：{{ w.createdBy || '—' }}（{{ w.createdAt || '—' }}）</span>
      </div>
      <div class="mw-reason">停水原因：{{ w.reason || '—' }}</div>
      <div v-if="w.revokeNote" class="mw-revoke">撤销说明：{{ w.revokeNote }}</div>

      <el-table v-if="w.impacts && w.impacts.length" :data="w.impacts" size="small" class="mw-table">
        <el-table-column label="冲击对象" min-width="220">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" style="margin-right:6px">{{ row.targetTypeName }}</el-tag>
            <span>{{ row.targetLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="重叠日期" width="190">
          <template #default="{ row }">
            {{ row.overlapStart }}<template v-if="row.overlapEnd && row.overlapEnd !== row.overlapStart"> ~ {{ row.overlapEnd }}</template>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="tag-line" :style="{ color: row.statusColor }">
              <i class="dot" :style="{ background: row.statusColor }"></i>{{ row.statusName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最终去向 / 例外依据" min-width="300">
          <template #default="{ row }">
            <span v-if="row.resolution">{{ row.resolution }}</span>
            <span v-else class="dim">待调度员处置</span>
            <div v-if="row.exceptionReason" class="exc">例外依据：{{ row.exceptionReason }}</div>
            <div v-if="row.handledBy" class="dim">处置：{{ row.handledBy }}（{{ row.handledAt || '—' }}）</div>
          </template>
        </el-table-column>
        <el-table-column v-if="w.status === 'ACTIVE'" label="避让处置" width="250">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="primary" plain :loading="loading" @click="shift(row)">整体顺延</el-button>
              <el-button size="small" plain :loading="loading" @click="split(row)">拆分</el-button>
              <el-button size="small" type="warning" plain :loading="loading" @click="keep(row)">例外保留</el-button>
            </template>
            <span v-else class="dim">已处置</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-tip" style="padding:8px">无重叠的灌溉计划与配水条</div>
    </div>

    <el-dialog v-model="dialog" :title="editingId ? '变更停水窗口' : '登记停水窗口'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="窗口编号"><el-input v-model="form.windowNo" /></el-form-item>
        <el-form-item label="停水渠段">
          <el-select v-model="form.canalSegmentId" style="width:100%">
            <el-option v-for="s in segments" :key="s.id" :value="s.id" :label="`${s.segmentCode} ${s.segmentName || ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="停水开始">
          <el-date-picker v-model="form.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" style="width:100%" />
        </el-form-item>
        <el-form-item label="停水结束">
          <el-date-picker v-model="form.endAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" style="width:100%" />
        </el-form-item>
        <el-form-item label="停水原因">
          <el-input v-model="form.reason" placeholder="如：节制闸大修，需断流施工" />
        </el-form-item>
        <el-form-item label="影响范围">
          <el-input v-model="form.scope" placeholder="如：全段停水 / 仅下游 2+000~4+000" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submit">{{ editingId ? '保存变更' : '登记生效' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mw-card { border-left: 4px solid var(--warn); }
.mw-card.revoked { border-left-color: var(--dead); opacity: 0.88; }
.mw-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.mw-no { font-size: 14px; color: var(--primary-deep); }
.mw-seg { font-size: 12px; color: var(--ink-soft); }
.mw-count { font-size: 11px; color: var(--ink-dim); }
.mw-actions { margin-left: auto; display: flex; gap: 6px; align-items: center; }
.mw-revoked-at { font-size: 11px; color: var(--ink-dim); }
.mw-meta { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 8px; font-size: 11.5px; color: var(--ink-soft); }
.mw-reason {
  margin-top: 8px; padding: 6px 10px; border-radius: 8px; font-size: 12px;
  color: #8d6e00; background: #fff8e1; border: 1px solid #ffecb3;
}
.mw-revoke { margin-top: 6px; font-size: 11.5px; color: var(--ink-dim); }
.mw-table { margin-top: 10px; }
.exc { margin-top: 4px; font-size: 11px; color: #8d6e00; }
.dim { font-size: 11px; color: var(--ink-dim); }
</style>
