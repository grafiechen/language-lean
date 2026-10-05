<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useAuth } from '../auth/store'
import { currentReviewScope } from '../../platform/web/reviewSync'
import { getJson, postJson } from '../../shared/api'
import ContentView from './ContentView.vue'
import { contributionKind, contributionStatus, type ContributionRow, type ContributionView } from './contributions'
import type { Results } from './types'
const props = withDefaults(defineProps<{ admin?: boolean }>(), { admin: false })
const auth = useAuth(), status = ref(props.admin ? 'PENDING_REVIEW' : ''), page = ref(0), busy = ref(false), error = ref(''), message = ref('')
const list = ref<Results<ContributionRow>>({ items: [], total: 0, page: 0 }), selected = ref<ContributionView | null>(null)
const dialog = ref<HTMLDialogElement | null>(null), note = ref('')
const base = props.admin ? '/api/v1/admin/contributions' : '/api/v1/contributions'
let sequence = 0
/** 在请求前后核对会话归属，账户切换后丢弃旧响应。 */
async function run(action: (valid: () => boolean) => Promise<void>) {
  if (busy.value) return
  const current = ++sequence, owner = auth.user?.id
  const valid = () => current === sequence && owner === auth.user?.id
  busy.value = true; error.value = ''
  try {
    if (!owner || !auth.serverAuthenticated || (await currentReviewScope()).userId !== owner) throw new Error('请重新登录当前账号。')
    await action(valid)
  } catch (cause) { if (valid()) error.value = cause instanceof Error ? cause.message : '操作失败。' }
  finally { if (valid()) busy.value = false }
}
/** 只读取当前筛选的一页摘要。 */
async function load(index = 0) {
  await run(async valid => {
    const result = await getJson<Results<ContributionRow>>(base + '?' + new URLSearchParams({ status: status.value, page: String(index) }))
    if ((await currentReviewScope()).userId !== auth.user?.id) throw new Error('登录账号已变化，请重新加载。')
    if (valid()) { list.value = result; page.value = index }
  })
}
/** 打开冻结快照，管理员不读取来源的当前私人正文。 */
async function open(id: string) {
  await run(async valid => {
    const result = await getJson<ContributionView>(base + '/' + id)
    if ((await currentReviewScope()).userId !== auth.user?.id) throw new Error('登录账号已变化。')
    if (valid()) { selected.value = result; note.value = ''; dialog.value?.showModal() }
  })
}
/** 通过、拒绝和撤回均携带审核版本；冲突后提供重新读取。 */
async function decide(action: 'approve' | 'reject' | 'withdraw') {
  if (!selected.value || busy.value) return
  if (action === 'reject' && !note.value.trim()) { error.value = '拒绝时请填写审核原因。'; return }
  if (action === 'approve' && !window.confirm('确认审核通过并公开本次提交的内容？')) return
  await run(async valid => {
    const value = await postJson<ContributionView>(base + '/' + selected.value!.row.id + '/' + action,
      { version: selected.value!.version, note: note.value }, { 'X-Learning-Account': auth.user!.id })
    if (valid()) { selected.value = value; message.value = action === 'approve' ? '已审核并公开，词典历史已追加。' : action === 'reject' ? '已拒绝，原公开版本保留。' : '已撤回。' }
    const result = await getJson<Results<ContributionRow>>(base + '?' + new URLSearchParams({ status: status.value, page: String(page.value) }))
    if (valid()) list.value = result
  })
}
/** 关闭对话框时不丢弃正在进行的请求。 */
function close() { if (!busy.value) { dialog.value?.close(); selected.value = null } }
/** 宿主页面离开时关闭顶层dialog，避免Ionic缓存页面残留弹窗。 */
function suspend() { sequence++; busy.value = false; dialog.value?.close(); selected.value = null; list.value = { items: [], total: 0, page: 0 } }
watch(() => auth.user?.id, () => { suspend(); void load() })
onMounted(() => load()); onBeforeUnmount(suspend)
defineExpose({ load, suspend })
</script>
<template>
  <section><div class="section-heading"><h2>{{ admin ? '贡献审核' : '我的投稿记录' }}</h2><button class="secondary" :disabled="busy" @click="load(page)">刷新列表</button></div>
    <p v-if="message" class="feedback" role="status">{{ message }}</p><p v-if="error && !selected" class="error" role="alert">{{ error }}</p>
    <form class="search-form" @submit.prevent="load()"><label>审核状态<select v-model="status" :disabled="busy"><option value="">全部</option><option value="PENDING_REVIEW">待审核</option><option value="APPROVED">已审核公开</option><option value="REJECTED">已拒绝</option><option value="WITHDRAWN">已撤回</option></select></label><button :disabled="busy">查询</button></form>
    <p v-if="!list.items.length" class="empty-state">没有匹配的投稿。</p>
    <div class="entry-list"><article v-for="row in list.items" :key="row.id" class="entry-row"><strong>{{ row.written }}</strong><span>{{ contributionKind(row.kind) }} · {{ contributionStatus(row.status) }} · {{ new Date(row.createdAt).toLocaleString() }}</span><button class="secondary" :disabled="busy" @click="open(row.id)">{{ admin ? '查看并审核' : '查看投稿' }}</button></article></div>
    <div class="pagination"><button class="secondary" :disabled="busy || page === 0" @click="load(page - 1)">上一页</button><span>{{ list.total }} 条 · 第 {{ page + 1 }} 页</span><button class="secondary" :disabled="busy || (page + 1) * 20 >= list.total" @click="load(page + 1)">下一页</button></div>
  </section>
  <dialog ref="dialog" class="config-dialog contribution-dialog" aria-labelledby="contribution-detail-title" @cancel.prevent="close">
    <template v-if="selected"><div class="section-heading"><h2 id="contribution-detail-title">{{ selected.row.written }} · {{ contributionKind(selected.row.kind) }}</h2><button class="quiet" :disabled="busy" @click="close">关闭</button></div>
      <p class="note">{{ contributionStatus(selected.row.status) }} · {{ selected.baseRevision ? '提交基于公开第 ' + selected.baseRevision + ' 版' : '新词条投稿' }}</p>
      <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" :disabled="busy" @click="open(selected.row.id)">重新读取申请</button></p>
      <p v-if="selected.submitNote" class="preserve-lines">投稿说明：{{ selected.submitNote }}</p><p v-if="selected.reviewNote" class="preserve-lines">审核意见：{{ selected.reviewNote }}</p>
      <p v-if="selected.row.publishedEntryId"><router-link :to="'/dictionary/' + selected.row.publishedEntryId" @click="close">查看公开词条（投稿发布于第 {{ selected.row.publishedRevision }} 版）</router-link></p>
      <p v-if="selected.row.kind === 'SUPPLEMENT'" class="note">补充仅追加未存在的读音、词义或例句，已有内容和子项身份保留。</p>
      <div class="contribution-comparison"><div v-if="selected.baseContent"><h3>提交时的公开基准</h3><ContentView :content="selected.baseContent" /></div><div><h3>本次提交快照</h3><ContentView :content="selected.content" /></div></div>
      <template v-if="selected.row.status === 'PENDING_REVIEW'"><template v-if="admin"><label>审核意见（拒绝时必填）<textarea v-model="note" maxlength="400" rows="3" :disabled="busy"></textarea></label><div class="dialog-actions"><button class="quiet danger" :disabled="busy" @click="decide('reject')">拒绝申请</button><button :disabled="busy" @click="decide('approve')">审核通过并公开</button></div></template><button v-else class="quiet danger" :disabled="busy" @click="decide('withdraw')">撤回申请</button></template>
    </template>
  </dialog>
</template>
<style scoped>
.contribution-dialog { width: min(1100px, calc(100vw - 2rem)); }
.contribution-comparison { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr)); gap: 1.5rem; }
.contribution-comparison > div { min-width: 0; overflow-wrap: anywhere; }
</style>
