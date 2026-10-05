<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getJson, postFile, postJson } from '../../shared/api'
import type { ImportBatch, ImportBatchSummary } from './types'

const batches = ref<ImportBatchSummary[]>([])
const selected = ref<ImportBatch | null>(null)
const file = ref<File | null>(null)
const busy = ref(false)
const error = ref('')
const message = ref('')

/** 页面刷新后仍可重新打开服务端保存的最近导入预览。 */
async function load() {
  batches.value = await getJson('/api/v1/admin/dictionary/imports')
}
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { await action() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '导入操作失败' }
  finally { busy.value = false }
}
function choose(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
}
/** 上传只生成预览，不会修改用户可见的基准词典。 */
async function preview() {
  if (!file.value) { error.value = '请先选择 JSON 文件。'; return }
  await run(async () => {
    selected.value = await postFile('/api/v1/admin/dictionary/imports/preview', 'file', file.value!)
    await load()
    message.value = '校验完成，请核对已存在和无效词条后再确认导入。'
  })
}
async function open(id: string) {
  await run(async () => { selected.value = await getJson('/api/v1/admin/dictionary/imports/' + id) })
}
/** 确认是批量发布动作；重复项始终跳过且不会覆盖。 */
async function apply() {
  if (!selected.value || !window.confirm(`确认发布 ${selected.value.readyCount} 个通过校验的新词条？`)) return
  await run(async () => {
    const result = await postJson<ImportBatch>('/api/v1/admin/dictionary/imports/' + selected.value!.id + '/apply',
      { version: selected.value!.version })
    selected.value = result
    await load()
    message.value = `导入完成：发布 ${result.importedCount} 个；预览时已存在 ${result.duplicateCount} 个；确认时新发现已存在 ${result.applyDuplicateCount} 个。`
  })
}
function rowClass(status: string) { return status === 'INVALID' ? 'error' : status === 'DUPLICATE' ? 'warning' : '' }
onMounted(() => run(load))
</script>

<template>
  <div class="import-layout">
    <section class="import-upload">
      <h2>上传规范化词典</h2>
      <p class="intro">上传后先做 UTF-8、内容和重复检查。确认前不会修改公开词典；已存在的词条会明确提示并跳过。</p>
      <p><a href="/examples/dictionary-import.sample.json" download>下载测试示例 JSON</a></p>
      <label>选择 JSON 文件<input type="file" accept="application/json,.json" :disabled="busy" @change="choose"></label>
      <button :disabled="busy || !file" @click="preview">上传并校验</button>
      <p class="note">单个文件最多 10 MB、5000 个词条。JMdict 全量数据使用仓库内的初始化 SQL；其他原始词典需先转换为这里的规范格式。</p>
      <p v-if="error" class="error feedback" role="alert">{{ error }}</p>
      <p v-if="message" class="feedback" role="status">{{ message }}</p>
    </section>

    <section>
      <h2>最近导入批次</h2>
      <p v-if="!batches.length" class="note">还没有导入记录。</p>
      <div class="entry-list">
        <button v-for="batch in batches" :key="batch.id" class="entry-row"
          :class="{ selected: selected?.id === batch.id }" :disabled="busy" @click="open(batch.id)">
          <strong>{{ batch.fileName }}</strong>
          <span>{{ batch.sourceName }} {{ batch.sourceVersion }} · {{ batch.status === 'APPLIED' ? '已确认' : '待确认' }}</span>
        </button>
      </div>
    </section>

    <section v-if="selected" class="import-report">
      <div class="section-heading"><h2>校验结果</h2><span class="tag">{{ selected.status === 'APPLIED' ? '已确认' : '待确认' }}</span></div>
      <p><strong>{{ selected.source.name }} {{ selected.source.version }}</strong> · {{ selected.source.license }}</p>
      <p class="note">文件：{{ selected.fileName }}<br>SHA-256：<code>{{ selected.sha256 }}</code></p>
      <div class="import-counts">
        <span>总数 {{ selected.totalCount }}</span><span>可导入 {{ selected.readyCount }}</span>
        <span class="warning">已存在 {{ selected.duplicateCount }}</span><span class="error">无效 {{ selected.invalidCount }}</span>
      </div>
      <p v-if="selected.status === 'APPLIED'">实际发布 {{ selected.importedCount }} 个；确认时新发现已存在 {{ selected.applyDuplicateCount }} 个。</p>
      <button v-else :disabled="busy || !selected.readyCount" @click="apply">确认并发布可导入词条</button>
      <div class="import-table-wrap"><table>
        <thead><tr><th>行</th><th>词条</th><th>语言</th><th>结果</th><th>说明</th></tr></thead>
        <tbody><tr v-for="row in selected.rows" :key="row.row" :class="rowClass(row.status)">
          <td>{{ row.row }}</td><td>{{ row.written || '—' }}</td><td>{{ row.languageCode || '—' }}</td>
          <td>{{ row.status === 'READY' ? '可导入' : row.status === 'DUPLICATE' ? '已存在' : '无效' }}</td><td>{{ row.message }}</td>
        </tr></tbody>
      </table></div>
      <p v-if="selected.rowsTruncated" class="note">页面只展示前 200 条，完整报告已保存在服务端批次记录中。</p>
    </section>
  </div>
</template>
