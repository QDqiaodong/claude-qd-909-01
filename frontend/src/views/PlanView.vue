<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const gantt = ref(null)
const loading = ref(false)
const dragId = ref(null)
const dragOver = ref('')
const activeAllotId = ref(null)
const hoursDraft = ref(null)

const dates = computed(() => gantt.value?.dates || [])
const plans = computed(() => gantt.value?.plans || [])
const allotments = computed(() => gantt.value?.allotments || [])
const dayLoads = computed(() => gantt.value?.dayLoads || [])

const loadMap = computed(() => {
  const map = {}
  dayLoads.value.forEach((d) => { map[`${d.canalSegmentId}|${d.allotDate}`] = d })
  return map
})

// 行 = 渠段（出现的渠段：有配水或有计划的都列出来）
const segments = computed(() => {
  const map = {}
  allotments.value.forEach((a) => {
    if (!map[a.canalSegmentId]) {
      map[a.canalSegmentId] = { id: a.canalSegmentId, code: a.segmentCode, name: a.segmentName, designFlow: a.designFlow }
    }
  })
  plans.value.forEach((p) => {
    if (!map[p.canalSegmentId]) {
      map[p.canalSegmentId] = { id: p.canalSegmentId, code: p.segmentCode, name: p.segmentName, designFlow: 0 }
    }
  })
  return Object.values(map)
})

function cellAllots(segmentId, date) {
  return allotments.value.filter((a) => a.canalSegmentId === segmentId && a.allotDate === date)
}

function cellLoad(segmentId, date) {
  return loadMap.value[`${segmentId}|${date}`] || null
}

function overDesign(segmentId, date) {
  const load = cellLoad(segmentId, date)
  if (!load) return false
  const total = Number(load.totalFlow || 0)
  const design = Number(load.designFlow || 0)
  return design > 0 && total > design
}

function dateIndex(date) {
  return dates.value.indexOf(date)
}

function barStyle(plan) {
  const total = dates.value.length || 1
  const s = Math.max(0, dateIndex(plan.startDate))
  const e = Math.max(s, dateIndex(plan.endDate) < 0 ? total - 1 : dateIndex(plan.endDate))
  return {
    left: (s / total * 100).toFixed(3) + '%',
    width: ((e - s + 1) / total * 100).toFixed(3) + '%'
  }
}

function onDragStart(a) {
  dragId.value = a.id
}

function onDragEnd() {
  dragId.value = null
  dragOver.value = ''
}

async function onDrop(segmentId, date) {
  const id = dragId.value
  dragOver.value = ''
  dragId.value = null
  if (!id) return
  const a = allotments.value.find((x) => x.id === id)
  if (!a || a.allotDate === date) return
  loading.value = true
  try {
    await api.plan.moveAllotment(id, date)
    ElMessage.success('配水已改到 ' + date)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function pickAllot(a) {
  activeAllotId.value = activeAllotId.value === a.id ? null : a.id
  hoursDraft.value = a.hours
}

async function saveHours(a) {
  loading.value = true
  try {
    await api.plan.updateAllotment(a.id, {
      planId: a.planId,
      canalSegmentId: a.canalSegmentId,
      allotDate: a.allotDate,
      flow: a.flow,
      hours: Number(hoursDraft.value),
      status: a.status,
      note: a.note
    })
    ElMessage.success('配水时长已更新')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function advance(plan) {
  loading.value = true
  try {
    await api.plan.advance(plan.id, '灌区调度')
    ElMessage.success('计划状态已推进')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function backToDraft(plan) {
  loading.value = true
  try {
    await api.plan.backToDraft(plan.id)
    ElMessage.success('计划已退回编制')
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
    gantt.value = await api.plan.gantt()
    activeAllotId.value = null
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">灌溉计划 · 周期甘特与配水排期</span>
      <span class="view-tip">拖动配水条改日期；某渠段某日合计流量超设计流量会标红</span>
    </div>

    <div class="panel" v-if="dates.length">
      <div class="panel-title">
        周期条（{{ gantt.startDate }} ~ {{ gantt.endDate }}）
      </div>
      <div v-if="!plans.length" class="empty-tip" style="padding:10px">暂无灌溉计划</div>
      <div v-for="p in plans" :key="p.id" class="plan-row">
        <div class="plan-track" :style="{ gridTemplateColumns: `150px repeat(${dates.length}, minmax(56px, 1fr))` }">
          <div class="plan-label">
            <b>{{ p.planName }}</b>
            <span>{{ p.segmentCode }} · {{ p.planDays }}天 · {{ p.planFlow }}m³/s</span>
          </div>
          <div class="plan-bar-host">
            <div class="plan-bar" :style="[barStyle(p), { background: p.statusColor }]">
              {{ p.planCode }} · {{ p.cropTypeName }}
            </div>
          </div>
        </div>
        <div class="plan-foot">
          <span class="tag-line" :style="{ color: p.statusColor }">
            <i class="dot" :style="{ background: p.statusColor }"></i>{{ p.statusName }}
          </span>
          <span class="op-meta">已排 {{ p.allottedVolume }} / 计划 {{ p.planVolume }} 万m³{{ p.locked ? '（已锁定，不可改水量）' : '' }}</span>
          <span class="plan-actions">
            <el-button size="small" type="primary" plain @click="advance(p)">推进状态</el-button>
            <el-button size="small" plain @click="backToDraft(p)">退回编制</el-button>
          </span>
        </div>
      </div>
    </div>

    <div class="panel" v-if="dates.length">
      <div class="panel-title">配水甘特（行 = 渠段，列 = 日期）</div>
      <div class="gantt-scroll">
        <div class="gantt" :style="{ gridTemplateColumns: `150px repeat(${dates.length}, minmax(56px, 1fr))` }">
          <div class="gt-corner">渠段 / 日期</div>
          <div v-for="d in dates" :key="'h' + d" class="gt-head">{{ d.slice(5) }}</div>

          <template v-for="seg in segments" :key="seg.id">
            <div class="gt-seg">
              <b>{{ seg.segmentCode }}</b>
              <span>设计 {{ seg.designFlow || '—' }} m³/s</span>
            </div>
            <div
              v-for="d in dates"
              :key="seg.id + '-' + d"
              class="gt-cell"
              :class="{ over: overDesign(seg.id, d), on: dragOver === seg.id + '|' + d }"
              @dragover.prevent="dragOver = seg.id + '|' + d"
              @dragleave="dragOver = ''"
              @drop="onDrop(seg.id, d)"
            >
              <div
                v-for="a in cellAllots(seg.id, d)"
                :key="a.id"
                class="gt-bar"
                :class="{ over: overDesign(seg.id, d), on: activeAllotId === a.id }"
                :title="a.planName + ' · ' + a.flow + 'm³/s × ' + a.hours + 'h'"
                draggable="true"
                @dragstart="onDragStart(a)"
                @dragend="onDragEnd"
                @click="pickAllot(a)"
              >
                {{ a.flow }}×{{ a.hours }}h
              </div>
              <div v-if="cellLoad(seg.id, d)" class="gt-load" :class="{ over: overDesign(seg.id, d) }">
                {{ cellLoad(seg.id, d).totalFlow }}
              </div>
            </div>
          </template>
        </div>
      </div>

      <div v-if="activeAllotId" class="edit-line">
        <span>已选配水条：改时长（h）</span>
        <el-input-number v-model="hoursDraft" :min="1" :max="24" size="small" />
        <el-button size="small" type="primary" :loading="loading" @click="saveHours(allotments.find(a => a.id === activeAllotId))">
          保存
        </el-button>
      </div>
      <div class="legend">
        <span class="tag-line"><i class="dot" style="background:#0288d1"></i>合计流量在设计流量内</span>
        <span class="tag-line"><i class="dot" style="background:#e53935"></i>合计流量超设计流量</span>
        <span class="tag-line">拖动色块改配水日期</span>
      </div>
    </div>

    <div class="panel" v-else><div class="empty-tip">暂无甘特数据</div></div>
  </div>
</template>

<style scoped>
.plan-row { margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px dashed var(--line); }
.plan-track { display: grid; align-items: center; gap: 2px; }
.plan-label { font-size: 11.5px; color: var(--ink-soft); }
.plan-label b { display: block; font-size: 12.5px; color: var(--ink); }
.plan-label span { font-size: 10.5px; color: var(--ink-dim); }
.plan-bar-host { position: relative; height: 26px; }
.plan-bar {
  position: absolute; top: 3px; height: 20px; border-radius: 6px;
  color: #fff; font-size: 11px; line-height: 20px; padding: 0 8px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  box-shadow: 0 3px 8px rgba(1, 87, 155, 0.22);
}
.plan-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 6px; }
.plan-actions { margin-left: auto; display: flex; gap: 6px; }
.op-meta { font-size: 11px; color: var(--ink-dim); }

.gantt-scroll { overflow-x: auto; }
.gantt { display: grid; gap: 2px; min-width: 640px; }
.gt-corner, .gt-head, .gt-seg {
  font-size: 10.5px; color: var(--ink-dim);
  padding: 4px 6px; background: var(--primary-wash); border-radius: 6px;
}
.gt-head { text-align: center; font-weight: 700; color: var(--primary-deep); }
.gt-seg b { display: block; font-size: 11.5px; color: var(--ink); }
.gt-cell {
  position: relative; min-height: 46px; padding: 4px 3px 12px;
  border-radius: 6px; background: #f7fcff; border: 1px solid var(--line);
}
.gt-cell.on { border-color: var(--primary); box-shadow: inset 0 0 0 2px rgba(2, 136, 209, 0.22); }
.gt-cell.over { background: #ffebee; border-color: #ef9a9a; }
.gt-bar {
  cursor: grab; border-radius: 6px; font-size: 10.5px; text-align: center;
  color: #fff; background: linear-gradient(120deg, var(--primary), var(--primary-soft));
  padding: 2px 0; margin-bottom: 3px; box-shadow: 0 2px 6px rgba(2, 136, 209, 0.24);
}
.gt-bar.on { outline: 2px solid #01579b; }
.gt-bar.over { background: linear-gradient(120deg, #e53935, #ef5350); }
.gt-load {
  position: absolute; left: 3px; bottom: 1px; right: 3px;
  font-size: 9.5px; text-align: right; color: var(--ink-dim);
}
.gt-load.over { color: var(--danger); font-weight: 800; }

.edit-line { display: flex; align-items: center; gap: 8px; margin-top: 10px; font-size: 12px; color: var(--ink-soft); }
.legend { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
</style>
