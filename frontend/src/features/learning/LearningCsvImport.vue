<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useAuth } from '../auth/store'
import { nativeLanguages } from '../dictionary/translations'
import { postFile } from '../../shared/api'

interface Report { fileHash: string; committed: boolean; added: number; linked: number; skipped: number; errors: number;
  rows: { line: number; written: string; status: string; message: string }[] }
const props = defineProps<{ bookId: string; online: boolean }>()
const emit = defineEmits<{ imported: [] }>()
const auth = useAuth(), dialog = ref<HTMLDialogElement | null>(null), file = ref<File | null>(null)
const translationLanguage = ref(auth.user?.nativeLanguage ?? 'zh-Hans')
const busy = ref(false), error = ref(''), report = ref<Report | null>(null)
let sequence = 0
/** 文件或译文语言改变后必须重新预检，不能确认陈旧报告。 */
function selectFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null; report.value = null; error.value = '' }
function open() { error.value = ''; dialog.value?.showModal() }
/** 上传原文件两次，服务器核对哈希并在确认事务中重新检查重复身份。 */
async function submit(confirm: boolean) {
  if (!file.value || busy.value || !props.online) return
  const current = ++sequence, userId = auth.user?.id, bookId = props.bookId
  busy.value = true; error.value = ''
  try {
    const response = await postFile<Report>('/api/v1/learning/wordbooks/' + bookId + '/csv/' + (confirm ? 'confirm' : 'preview'), 'file', file.value,
      { translationLanguage: translationLanguage.value, ...(confirm ? { fileHash: report.value!.fileHash } : {}) })
    if (current !== sequence || auth.user?.id !== userId || props.bookId !== bookId) return
    report.value = response
    if (response.committed) emit('imported')
  } catch (cause) { if (current === sequence) error.value = cause instanceof Error ? cause.message : 'CSV导入失败，请重试。' }
  finally { if (current === sequence) busy.value = false }
}
/** 下载带UTF-8 BOM的模板，桌面表格软件也能正确识别中文。 */
function template() {
  const blob = new Blob(['\uFEFFwritten,reading,gloss,tags,example,exampleReading,exampleTranslation\n該当,がいとう,符合,N2,この条件に該当します。,この条件に該当します。,符合这个条件。\n'], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'language-lean-个人词条模板.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
watch(translationLanguage, () => { report.value = null })
/** Ionic保留页面时也关闭弹窗，切换账户清除原账户文件和报告。 */
watch(() => [auth.user?.id, props.bookId], () => { sequence++; busy.value = false; report.value = null; file.value = null; dialog.value?.close() })
onBeforeRouteLeave(() => dialog.value?.close())
onBeforeUnmount(() => { sequence++; dialog.value?.close() })
</script>
<template>
  <button class="secondary" :disabled="!online" @click="open">导入个人 CSV</button>
  <dialog ref="dialog" class="config-dialog study-dialog" aria-labelledby="learning-csv-title" @cancel="busy && $event.preventDefault()">
    <div class="section-heading"><h2 id="learning-csv-title">导入个人词条</h2><button class="quiet" :disabled="busy" aria-label="关闭CSV导入" @click="dialog?.close()">关闭</button></div>
    <p class="study-muted">日语单词本：UTF-8 CSV，最大2MB、1000行。先预检再确认；错误行和重复行会跳过。</p>
    <p class="study-muted">同一写法优先复用自己的词条和进度，再引用基准词典；未收录的创建为私有词条。已有内容不会覆盖。</p>
    <button class="quiet" @click="template">下载 CSV 模板</button>
    <form class="editor-fields" @submit.prevent="submit(false)">
      <label>CSV文件<input type="file" accept=".csv,text/csv" :disabled="busy" @change="selectFile"></label>
      <label>文件中释义和译文的语言<select v-model="translationLanguage" :disabled="busy"><option v-for="language in nativeLanguages" :key="language.code" :value="language.code">{{ language.name }}</option></select></label>
      <p class="study-muted">列顺序：单词、假名、释义、标签（用 | 分隔）、例句、例句发音、例句译文。前3列必需，假名可留空；后4列可省略。支持英文表头及无表头文件。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions"><button class="secondary" :disabled="busy || !file || !online">{{ busy ? '正在处理…' : '预检 CSV' }}</button><button type="button" :disabled="busy || !online || !report || report.committed || !(report.added + report.linked)" @click="submit(true)">确认导入有效词条</button></div>
    </form>
    <section v-if="report" aria-label="CSV导入报告">
      <p role="status">{{ report.committed ? '导入完成' : '预检完成' }}：新增 {{ report.added }}、复用 {{ report.linked }}、跳过 {{ report.skipped }}、错误 {{ report.errors }}。</p>
      <ol class="entry-list"><li v-for="row in report.rows" :key="row.line" class="entry-row"><strong>第 {{ row.line }} 行 · {{ row.written || '空写法' }}</strong><span :class="row.status === 'ERROR' ? 'error' : 'study-muted'">{{ row.message }}</span></li></ol>
    </section>
  </dialog>
</template>
