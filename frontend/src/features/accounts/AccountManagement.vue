<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAuth } from '../auth/store'
import { getJson, postJson } from '../../shared/api'
import AccountClosureDialog from './AccountClosureDialog.vue'

type Account = { id: string; username: string; email: string; status: 'ACTIVE' | 'DISABLED'; roles: string[]; mustChangePassword: boolean; version: number }
const auth = useAuth()
const list = ref<{ items: Account[]; total: number; page: number }>({ items: [], total: 0, page: 0 })
const q = ref(''), status = ref(''), busy = ref(false), error = ref(''), message = ref(''), modalError = ref('')
const mail = ref({ configured: false, recoveryConfigured: false })
const dialog = ref<HTMLDialogElement | null>(null)
const closure = ref<InstanceType<typeof AccountClosureDialog> | null>(null)
async function deleted() { message.value = '账号及个人数据已删除。'; await search() }
const form = ref({ username: '', email: '', administrator: false })
/** 分页仅展示账号身份和状态，不读取个人学习内容。 */
async function load(page = 0) {
  const params = new URLSearchParams({ q: q.value, page: String(page) })
  if (status.value) params.set('status', status.value)
  const [rows, readiness] = await Promise.all([
    getJson<typeof list.value>('/api/v1/admin/accounts?' + params),
    getJson<typeof mail.value>('/api/v1/admin/accounts/mail'),
  ])
  list.value = rows; mail.value = readiness
}
async function search(page = 0) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await load(page) } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取失败，请重试' }
  finally { busy.value = false }
}
/** 点击新增后才显示录入表单，初始密码通过邮件发送。 */
function create() {
  form.value = { username: '', email: '', administrator: false }; modalError.value = ''; dialog.value?.showModal()
}
async function save() {
  if (busy.value) return
  busy.value = true; modalError.value = ''; message.value = ''
  try {
    await postJson('/api/v1/admin/accounts', form.value)
    dialog.value?.close(); message.value = '账号已创建，初始密码已发送到该账号邮箱。'
    // 创建成功后不能因刷新失败而再次发送创建请求。
    try { await load() } catch { error.value = '账号已创建，列表刷新失败，请点击搜索重新读取。' }
  } catch (cause) { modalError.value = cause instanceof Error ? cause.message : '创建失败，请重试' }
  finally { busy.value = false }
}
/** 携带版本号变更状态，服务器负责最后管理员保护。 */
async function toggle(account: Account) {
  if (busy.value) return
  const next = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED' && !window.confirm(`禁用 ${account.username}？该账号将无法登录，已有在线会话会失效，学习数据会保留。`)) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await postJson(`/api/v1/admin/accounts/${account.id}/status`, { version: account.version, status: next })
    message.value = next === 'ACTIVE' ? '账号已启用，需要重新登录。' : '账号已禁用。'
    await load(list.value.page)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '修改失败，请刷新后重试' }
  finally { busy.value = false }
}
onMounted(() => void search())
</script>
<template>
  <section>
    <div class="section-heading"><h2>账号管理 <small>{{ list.total }}</small></h2><button :disabled="busy || !mail.configured" @click="create">新增账号</button></div>
    <p class="note">初始密码通过邮件发送；账号可使用用户名或邮箱登录。</p>
    <p v-if="!mail.configured" class="note">账号邮件尚未配置，暂时不能创建账号。请配置邮件服务后刷新。</p>
    <p v-else-if="!mail.recoveryConfigured" class="note">初始密码邮件已就绪；配置站点访问地址后即可使用找回密码。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <form class="search-form" @submit.prevent="search()">
      <label>用户名或邮箱<input v-model="q" maxlength="320" placeholder="输入账号信息"></label>
      <label>账号状态<select v-model="status"><option value="">全部</option><option value="ACTIVE">已启用</option><option value="DISABLED">已禁用</option></select></label>
      <button class="secondary" :disabled="busy">搜索</button>
    </form>
    <div class="account-table-wrap">
      <table class="account-table"><thead><tr><th>用户名</th><th>邮箱</th><th>角色</th><th>状态</th><th>密码</th><th>操作</th></tr></thead>
        <tbody><tr v-for="account in list.items" :key="account.id">
          <td>{{ account.username }}<small v-if="account.id === auth.user?.id">（当前账号）</small></td>
          <td>{{ account.email }}</td><td>{{ account.roles.includes('ADMIN') ? '管理员' : '用户' }}</td>
          <td><span class="tag">{{ account.status === 'ACTIVE' ? '已启用' : '已禁用' }}</span></td>
          <td>{{ account.mustChangePassword ? '需修改初始密码' : '已设置' }}</td>
          <td><div class="actions"><button class="secondary" :disabled="busy || account.id === auth.user?.id" @click="toggle(account)">{{ account.status === 'ACTIVE' ? '禁用' : '启用' }}</button><button class="secondary danger" :disabled="busy || account.id === auth.user?.id" @click="closure?.open(account)">删除</button></div></td>
        </tr></tbody>
      </table>
    </div>
    <p v-if="!list.items.length" class="note">{{ busy ? '正在加载…' : '没有符合条件的账号。' }}</p>
    <div class="pagination"><button class="secondary" :disabled="busy || list.page === 0" @click="search(list.page - 1)">上一页</button><span>{{ list.page + 1 }}</span><button class="secondary" :disabled="busy || (list.page + 1) * 20 >= list.total" @click="search(list.page + 1)">下一页</button></div>
  </section>
  <dialog ref="dialog" class="config-dialog" aria-labelledby="new-account-title" @cancel="busy && $event.preventDefault()">
    <form @submit.prevent="save"><h2 id="new-account-title">新增账号</h2>
      <p class="note">创建后将向下方邮箱发送初始密码，请确认邮箱填写正确。</p>
      <p v-if="modalError" class="error" role="alert">{{ modalError }}</p>
      <fieldset :disabled="busy">
        <label>用户名<input v-model="form.username" required maxlength="80" autocomplete="off"></label>
        <label>邮箱<input v-model="form.email" type="email" required maxlength="320" autocomplete="off"></label>
        <label class="check-label account-admin-check"><input v-model="form.administrator" type="checkbox">设为管理员</label>
        <p v-if="form.administrator" class="note">管理员可维护公开词典、系统配置和其他账号。</p>
        <div class="actions"><button type="button" class="secondary" @click="dialog?.close()">取消</button><button type="submit">{{ busy ? '正在创建并发送邮件…' : '创建并发送初始密码' }}</button></div>
      </fieldset>
    </form>
  </dialog>
  <AccountClosureDialog ref="closure" admin @deleted="deleted" />
</template>
<style scoped>
.account-admin-check { margin: 16px 0; gap: 9px; }
.account-admin-check input { width: 18px; height: 18px; min-height: 18px; margin: 0; }
</style>
