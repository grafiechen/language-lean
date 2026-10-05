<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { IonPage, IonContent, onIonViewWillEnter, onIonViewWillLeave } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
import { getJson, postJson } from '../shared/api'
import { currentReviewScope } from '../platform/web/reviewSync'
import ContentEditor from '../features/dictionary/ContentEditor.vue'
import ContributionList from '../features/dictionary/ContributionList.vue'
import { contributionDraft, contributionRetry, type ContributionRequest, type ContributionView } from '../features/dictionary/contributions'
import type { LearningContent } from '../features/learning/personalContent'
import type { PrivateEntry } from '../features/learning/privateEntries'
const auth = useAuth(), route = useRoute(), list = ref<InstanceType<typeof ContributionList> | null>(null)
const dialog = ref<HTMLDialogElement | null>(null), draft = ref<ReturnType<typeof contributionDraft> | null>(null), written = ref('')
const busy = ref(false), error = ref(''), message = ref(''), note = ref(''), publish = ref(false)
let sequence = 0, owner = '', retry: ContributionRequest | null = null
/** 只从本人来源加载白名单正文，申请表单不带笔记标签。 */
async function start() {
  const privateId = typeof route.query.privateEntry === 'string' ? route.query.privateEntry : ''
  const learningId = typeof route.query.learningItem === 'string' ? route.query.learningItem : ''
  if (!privateId && !learningId) return
  const current = ++sequence, userId = auth.user?.id; busy.value = true; error.value = ''
  try {
    if (!userId || !auth.serverAuthenticated || (await currentReviewScope()).userId !== userId) throw new Error('请重新登录当前账号。')
    if (privateId && learningId) throw new Error('请选择一个投稿来源。')
    const source = privateId ? await getJson<PrivateEntry>('/api/v1/learning/private-entries/' + encodeURIComponent(privateId))
      : await getJson<LearningContent>('/api/v1/learning/items/' + encodeURIComponent(learningId) + '/content')
    if ((await currentReviewScope()).userId !== userId) throw new Error('登录账号已变化。')
    if (current !== sequence || userId !== auth.user?.id) return
    owner = userId; written.value = 'entry' in source ? source.entry.written : source.written
    draft.value = contributionDraft(source); retry = null; publish.value = false; note.value = ''; dialog.value?.showModal()
  } catch (cause) { if (current === sequence) error.value = cause instanceof Error ? cause.message : '加载来源失败。' }
  finally { if (current === sequence) busy.value = false }
}
/** 请求失败可用相同UUID重试；编辑内容后生成新UUID。 */
async function submit() {
  if (busy.value || !draft.value || !publish.value) return
  const current = sequence, account = owner; busy.value = true; error.value = ''
  try {
    if (account !== auth.user?.id || (await currentReviewScope()).userId !== account) throw new Error('登录账号已变化，请重新打开表单。')
    retry = contributionRetry({ ...draft.value, note: note.value, publishRequested: publish.value }, retry)
    await postJson<ContributionView>('/api/v1/contributions', retry, { 'X-Learning-Account': account })
    if (current !== sequence || account !== auth.user?.id) return
    dialog.value?.close(); draft.value = null; message.value = '投稿已提交。审核前公开词典保持原版；私有内容及学习进度保留。'
  } catch (cause) { if (current === sequence && account === auth.user?.id) error.value = cause instanceof Error ? cause.message : '提交失败，可重试。' }
  finally { if (current === sequence) busy.value = false }
  if (current === sequence && !draft.value) await list.value?.load()
}
/** 关闭表单只丢弃未提交编辑，正在上传时保留表单。 */
function close() { if (!busy.value) { dialog.value?.close(); draft.value = null } }
function suspend() { sequence++; dialog.value?.close(); draft.value = null; busy.value = false; list.value?.suspend() }
onBeforeRouteLeave(() => !busy.value)
watch(() => [route.query.privateEntry, route.query.learningItem], () => { suspend(); void start() })
watch(() => auth.user?.id, suspend)
onIonViewWillEnter(() => { void start(); void list.value?.load() }); onIonViewWillLeave(suspend); onBeforeUnmount(suspend)
</script>
<template>
  <ion-page><ion-content><main class="workspace"><header class="account-bar"><router-link to="/">← 学习首页</router-link><router-link to="/private-entries">我的私有词条</router-link></header>
    <p class="brand">LANGUAGE LEAN · 内容贡献</p><h1>我的投稿</h1><p class="intro">从私有词条选择“提交审核”，或从学习内容选择“提交公开修订”。审核通过后追加到基准词典的历史版本。</p>
    <p v-if="error && !draft" class="error" role="alert">{{ error }} <button class="quiet" :disabled="busy" @click="start">重新加载来源</button></p><p v-if="message" class="feedback" role="status">{{ message }}</p>
    <ContributionList ref="list" />
    <dialog ref="dialog" class="config-dialog" aria-labelledby="contribution-submit-title" @cancel.prevent="close"><template v-if="draft">
      <div class="section-heading"><h2 id="contribution-submit-title">投稿：{{ written }}</h2><button class="quiet" :disabled="busy" @click="close">关闭</button></div>
      <p class="note">只发送下方明确确认的内容。请移除不希望公开的例句；笔记、标签和学习记录不会提交。提交后快照固定，来源编辑不会改变本申请。</p>
      <p class="note">{{ draft.privateEntryId ? '同写法已有公开词条时作为补充，只追加新内容。' : '此申请是公开内容修订，审核通过后替换公开版本，请核对完整内容。' }}</p>
      <p v-if="error" class="error" role="alert">{{ error }} <button type="button" class="quiet" :disabled="busy" @click="start">重新加载来源</button></p>
      <form @submit.prevent="submit"><fieldset class="editor-fields" :disabled="busy"><ContentEditor v-model="draft.content" personal />
        <label>投稿说明<textarea v-model="note" maxlength="500" rows="3"></textarea></label><label class="checkbox-label"><input v-model="publish" required type="checkbox">申请审核通过后公开本次快照</label>
        <div class="dialog-actions"><button class="quiet" type="button" @click="close">取消</button><button type="submit" :disabled="!publish">{{ busy ? '正在提交…' : '提交审核' }}</button></div>
      </fieldset></form>
    </template></dialog>
  </main></ion-content></ion-page>
</template>
