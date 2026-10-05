<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getJson, postJson } from '../../shared/api'
import type { SystemDictionary, SystemDictionaryItem, SystemDictionarySummary } from './types'

const dictionaries = ref<SystemDictionarySummary[]>([])
const selected = ref<SystemDictionary | null>(null)
const dictionaryForm = ref({ code: '', displayName: '', description: '', enabled: true, version: null as number | null })
const itemForm = ref({ id: '', value: '', displayName: '', description: '', sortOrder: 10, enabled: true, version: null as number | null })
const dictionaryDialog = ref<HTMLDialogElement | null>(null)
const itemDialog = ref<HTMLDialogElement | null>(null)
const busy = ref(false)
const error = ref('')
const message = ref('')

/** 列出所有配置字典，新进入页面时优先打开内容来源。 */
async function load(preferred?: string) {
  dictionaries.value = await getJson('/api/v1/admin/system-dictionaries')
  const code = preferred ?? selected.value?.code ?? dictionaries.value.find(item => item.code === 'CONTENT_SOURCE')?.code
  if (code) await open(code)
}
/** 选择字典只显示详情，不直接进入编辑状态。 */
async function open(code: string) {
  const dictionary = await getJson<SystemDictionary>('/api/v1/admin/system-dictionaries/' + encodeURIComponent(code))
  selected.value = dictionary
  resetItem(); error.value = ''; message.value = ''
}
/** 新增和编辑字典都在弹窗内完成，主页面保持为清晰的列表。 */
function newDictionary() {
  dictionaryForm.value = { code: '', displayName: '', description: '', enabled: true, version: null }
  error.value = ''; message.value = ''; dictionaryDialog.value?.showModal()
}
function editDictionary() {
  if (!selected.value) return
  dictionaryForm.value = { code: selected.value.code, displayName: selected.value.displayName,
    description: selected.value.description, enabled: selected.value.enabled, version: selected.value.version }
  error.value = ''; message.value = ''; dictionaryDialog.value?.showModal()
}
/** 选项 value 进入业务快照，创建后只允许修改显示信息。 */
function editItem(item: SystemDictionaryItem) {
  itemForm.value = { ...item }; error.value = ''; message.value = ''; itemDialog.value?.showModal()
}
function newItem() {
  resetItem(); error.value = ''; message.value = ''; itemDialog.value?.showModal()
}
function resetItem() {
  itemForm.value = { id: '', value: '', displayName: '', description: '', sortOrder: 10, enabled: true, version: null }
}
/** 统一处理保存状态和可读错误。 */
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { await action() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { busy.value = false }
}
async function saveDictionary() {
  await run(async () => {
    const code = dictionaryForm.value.code.trim().toUpperCase()
    await postJson('/api/v1/admin/system-dictionaries/' + encodeURIComponent(code), dictionaryForm.value)
    await load(code); dictionaryDialog.value?.close(); message.value = '系统字典已保存'
  })
}
async function saveItem() {
  if (!selected.value) return
  await run(async () => {
    const base = '/api/v1/admin/system-dictionaries/' + encodeURIComponent(selected.value!.code) + '/items'
    selected.value = await postJson<SystemDictionary>(itemForm.value.id ? base + '/' + itemForm.value.id : base, itemForm.value)
    await load(selected.value.code); itemDialog.value?.close(); message.value = '字典选项已保存'
  })
}
onMounted(() => run(() => load()))
</script>

<template>
  <div>
    <div class="section-heading"><div><h2>系统字典</h2><p class="note compact-note">维护页面下拉选项。停用不会改写已经发布的词条历史。</p></div>
      <button class="secondary" :disabled="busy" @click="newDictionary">新增字典</button></div>
    <p v-if="error" class="error feedback" role="alert">{{ error }}</p>
    <p v-if="message" class="feedback" role="status">{{ message }}</p>
    <div class="admin-grid">
      <section class="catalog-panel">
        <h3>字典目录</h3>
        <div class="entry-list">
          <button v-for="dictionary in dictionaries" :key="dictionary.code" class="entry-row"
                  :class="{ selected: selected?.code === dictionary.code }" :disabled="busy" @click="open(dictionary.code)">
            <strong>{{ dictionary.displayName }}</strong>
            <span>{{ dictionary.code }} · {{ dictionary.itemCount }} 项 · {{ dictionary.enabled ? '已启用' : '已停用' }}</span>
          </button>
        </div>
      </section>
      <section v-if="selected" class="editor-panel">
          <div class="section-heading"><div><h3>{{ selected.displayName }}</h3><p class="note compact-note">{{ selected.description || '暂无用途说明' }}</p></div>
            <button class="secondary" :disabled="busy" @click="editDictionary">编辑字典</button></div>
          <div class="section-heading dictionary-items-heading"><h3>选项列表 <small>{{ selected.items.length }}</small></h3><button class="secondary" :disabled="busy" @click="newItem">新增选项</button></div>
          <p v-if="!selected.items.length" class="empty-state">还没有选项，点击“新增选项”添加。</p>
          <div class="entry-list">
            <button v-for="item in selected.items" :key="item.id" class="entry-row"
                    :disabled="busy" @click="editItem(item)">
              <strong>{{ item.displayName }}</strong><span>保存值：{{ item.value }} · 排序 {{ item.sortOrder }} · {{ item.enabled ? '下拉框中显示' : '已停用' }}</span>
              <span v-if="item.description">{{ item.description }}</span>
            </button>
          </div>
      </section>
      <section v-else class="editor-panel empty-state">请选择左侧字典，或点击“新增字典”。</section>
    </div>

    <dialog ref="dictionaryDialog" class="config-dialog" aria-labelledby="dictionary-dialog-title">
      <form class="editor-fields" @submit.prevent="saveDictionary">
        <fieldset :disabled="busy">
          <div class="section-heading"><h3 id="dictionary-dialog-title">{{ dictionaryForm.version === null ? '新增字典' : '编辑字典' }}</h3>
            <button type="button" class="quiet" @click="dictionaryDialog?.close()">关闭</button></div>
          <div class="form-grid">
            <label>字典代码<input v-model="dictionaryForm.code" required maxlength="64" :disabled="dictionaryForm.version !== null" placeholder="例如 CONTENT_SOURCE"></label>
            <label>显示名称<input v-model="dictionaryForm.displayName" required maxlength="80" placeholder="例如 内容来源"></label>
          </div>
          <label>用途说明<textarea v-model="dictionaryForm.description" maxlength="500" rows="3"></textarea></label>
          <label class="check-label"><input v-model="dictionaryForm.enabled" type="checkbox">允许该字典向页面提供选项</label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div class="dialog-actions"><button type="button" class="secondary" @click="dictionaryDialog?.close()">取消</button><button type="submit">保存字典</button></div>
        </fieldset>
      </form>
    </dialog>

    <dialog ref="itemDialog" class="config-dialog" aria-labelledby="item-dialog-title" @cancel="resetItem">
      <form class="editor-fields" @submit.prevent="saveItem">
            <fieldset :disabled="busy">
              <div class="section-heading"><h3 id="item-dialog-title">{{ itemForm.id ? '编辑选项' : '新增选项' }}</h3>
                <button type="button" class="quiet" @click="itemDialog?.close()">关闭</button></div>
              <div class="form-grid">
                <label>保存值<input v-model="itemForm.value" required maxlength="200" :disabled="!!itemForm.id" placeholder="例如 手工录入"></label>
                <label>显示名称<input v-model="itemForm.displayName" required maxlength="200"></label>
                <label>排序<input v-model.number="itemForm.sortOrder" type="number" min="-100000" max="100000" required></label>
              </div>
              <label>选项说明<textarea v-model="itemForm.description" maxlength="500" rows="2"></textarea></label>
              <label class="check-label"><input v-model="itemForm.enabled" type="checkbox">在“{{ selected?.displayName }}”下拉框中显示</label>
              <p class="field-note">关闭后会从新的选择中隐藏，已经保存到词条历史中的内容不会改变。</p>
              <p v-if="error" class="error" role="alert">{{ error }}</p>
              <div class="dialog-actions"><button type="button" class="secondary" @click="itemDialog?.close()">取消</button><button type="submit">保存选项</button></div>
            </fieldset>
      </form>
    </dialog>
  </div>
</template>
