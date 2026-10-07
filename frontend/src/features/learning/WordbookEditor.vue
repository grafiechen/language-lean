<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getJson, putJson } from '../../shared/api'
import type { AccountScope } from '../../core/reviews'
import type { Wordbook } from './types'
import { accountKey } from '../../platform/web/database'
import { cacheWordbookMetadata } from '../../platform/web/wordbookMetadata'
const props = defineProps<{ scope: AccountScope | null; online: boolean }>()
const emit = defineEmits<{ saved: [book: Wordbook, warning: string] }>()
const dialog = ref<HTMLDialogElement | null>(null), current = ref<Wordbook | null>(null), latest = ref<Wordbook | null>(null)
const name = ref(''), description = ref(''), busy = ref(false), error = ref('')
let owner: AccountScope | null = null, sequence = 0
const sameOwner = () => !!owner && !!props.scope && accountKey(owner) === accountKey(props.scope) && props.online
/** 表单固定打开时的账号和基准版本；普通取消不提交任何修改。 */
function open(book: Wordbook) {
  if (!props.scope || !props.online) return
  sequence++; owner = { ...props.scope }; current.value = { ...book }; latest.value = null
  name.value = book.name; description.value = book.description; busy.value = false; error.value = ''
  if (book.version == null) error.value = '请重新读取单词本后再编辑。'
  dialog.value?.showModal()
}
function close() { sequence++; dialog.value?.close(); owner = null; current.value = null; latest.value = null; busy.value = false }
/** 冲突后先展示当前服务器内容，保留输入，不能自动替用户选择覆盖。 */
async function reload() {
  if (!sameOwner() || !current.value || busy.value) return
  const request = ++sequence, id = current.value.id; busy.value = true; error.value = ''
  try {
    const rows = await getJson<Wordbook[]>('/api/v1/learning/wordbooks')
    if (request !== sequence || !sameOwner()) return
    const value = rows.find(book => book.id === id)
    if (!value || value.version == null) throw new Error('单词本已删除或服务器未提供编辑版本。')
    latest.value = value
  } catch (cause) { if (request === sequence) error.value = cause instanceof Error ? cause.message : '读取失败，请重试。' }
  finally { if (request === sequence) busy.value = false }
}
/** 必须显式选择采用服务器内容或保留输入，随后仍需点击保存才会提交。 */
function adopt(keepInput: boolean) {
  if (!latest.value) return
  current.value = latest.value
  if (!keepInput) { name.value = latest.value.name; description.value = latest.value.description }
  latest.value = null; error.value = ''
}
async function save() {
  if (!sameOwner() || !current.value || current.value.version == null || busy.value) return
  const actor = { ...owner! }, id = current.value.id, request = ++sequence; busy.value = true; error.value = ''
  try {
    const value = await putJson<Wordbook>('/api/v1/learning/wordbooks/' + id,
      { accountId: actor.userId, version: current.value.version, name: name.value, description: description.value })
    let warning = ''
    try { await cacheWordbookMetadata(actor, value) }
    catch { warning = '已保存到服务器，本机离线名称更新失败，请联网核对后再使用离线内容。' }
    if (request !== sequence || !sameOwner()) return
    emit('saved', value, warning); close()
  } catch (cause) { if (request === sequence && sameOwner()) error.value = cause instanceof Error ? cause.message : '保存失败，输入仍保留。' }
  finally { if (request === sequence) busy.value = false }
}
watch(() => props.scope ? accountKey(props.scope) : '', close)
onBeforeUnmount(close); defineExpose({ open, close })
</script>
<template>
  <dialog ref="dialog" class="config-dialog" aria-labelledby="wordbook-edit-title" @cancel="busy && $event.preventDefault()" @close="close">
    <h2 id="wordbook-edit-title">编辑单词本</h2><p class="note">修改名称和说明，保留所有词条、共享学习进度与未完成训练。</p>
    <p v-if="!online" class="note">当前离线，修改尚未确认提交。输入保留，联网后可继续保存。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form @submit.prevent="save"><fieldset :disabled="busy || !sameOwner()">
      <label>名称<input v-model="name" maxlength="100" required></label><label>说明<textarea v-model="description" maxlength="500" rows="3"></textarea></label>
      <div v-if="latest" class="conflict"><h3>服务器当前版本</h3><strong>{{ latest.name }}</strong><p>{{ latest.description || '没有说明' }}</p>
        <div class="actions"><button type="button" class="secondary" @click="adopt(false)">采用服务器内容</button><button type="button" class="secondary" @click="adopt(true)">保留输入并采用最新基准</button></div></div>
      <button v-if="error || current?.version == null" type="button" class="quiet" @click="reload">读取服务器当前版本</button>
      <div class="dialog-actions"><button type="button" class="secondary" @click="close">取消</button><button :disabled="!name.trim() || current?.version == null || !!latest">{{ busy ? '正在保存…' : '保存修改' }}</button></div>
    </fieldset></form>
  </dialog>
</template>
<style scoped>.conflict { padding: 14px; border: 1px solid #dfe4da; border-radius: 8px; overflow-wrap: anywhere }</style>
