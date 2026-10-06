<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getJson, postJson } from '../../shared/api'
import { backupFailure, backupStatus, type BackupJob, type DatabaseBackups } from './backups'
const data = ref<DatabaseBackups | null>(null), busy = ref(false), error = ref(''), message = ref('')
let sequence = 0, active = true, timer: ReturnType<typeof setTimeout> | undefined, requestId: string | undefined
const pending = computed(() => data.value?.jobs.some(job => ['QUEUED', 'RUNNING'].includes(job.state)) ?? false)
/** 执行期间短暂轮询；离开页面后立即停止，不能保留旧账号的后台任务视图。 */
async function load() {
  active = true; const current = ++sequence; busy.value = true; error.value = ''; clearTimeout(timer)
  try {
    const value = await getJson<DatabaseBackups>('/api/v1/admin/system/backups')
    if (current !== sequence || !active) return
    data.value = value
    if (pending.value) timer = setTimeout(() => void load(), 3000)
  } catch (cause) { if (current === sequence && active) error.value = cause instanceof Error ? cause.message : '备份记录加载失败。' }
  finally { if (current === sequence) busy.value = false }
}
/** 未确认响应的重试沿用同一请求标识，不反复创建新的转储任务。 */
async function start() {
  if (busy.value || !data.value?.ready || pending.value) return
  const current = ++sequence; busy.value = true; error.value = ''; message.value = ''; clearTimeout(timer)
  requestId ??= crypto.randomUUID()
  try {
    await postJson<BackupJob>('/api/v1/admin/system/backups', { requestId })
    if (current !== sequence || !active) return
    requestId = undefined; message.value = '备份请求已确认，请查看执行记录。'; await load()
  } catch (cause) { if (current === sequence && active) error.value = cause instanceof Error ? cause.message : '备份请求未确认，请重试。' }
  finally { if (current === sequence) busy.value = false }
}
function suspend() { active = false; sequence++; clearTimeout(timer); busy.value = false; data.value = null; requestId = undefined }
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
onMounted(load); onBeforeUnmount(suspend); defineExpose({ load, suspend })
</script>
<template>
  <section aria-labelledby="database-backups-title" :aria-busy="busy">
    <div class="section-heading"><h3 id="database-backups-title">服务器数据库备份</h3><button class="secondary" :disabled="busy" @click="load">刷新记录</button></div>
    <p class="note">服务器灾备用于恢复数据库，不提供个人导出或网页下载。音频文件和服务器配置不在数据库归档内，需分别维护。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <template v-if="data">
      <p>备份功能：{{ data.enabled ? '已开启' : '未开启' }} · 转储工具：{{ data.databaseDumpConfigured ? '已就绪' : '未就绪' }} · 独立加密密钥：{{ data.encryptionConfigured ? '已配置' : '未配置' }} · 私有备份存储：{{ data.storageConfigured ? '已配置' : '未配置' }}</p>
      <p class="note">定时频率：{{ data.intervalHours > 0 ? `每${data.intervalHours}小时` : '未启用定时备份' }}；保留策略：{{ data.retentionDays > 0 ? `${data.retentionDays}天（保留最新已验证归档）` : '不自动清理成功备份' }}。</p>
      <p v-if="!data.policyValid" class="error">备份策略参数不合法，请调整服务器配置。</p>
      <p v-if="!data.ready" class="note">请在服务器配置备份镜像、独立密钥和私有R2存储后开启。页面不会读取或修改凭据。</p>
      <button :disabled="busy || !data.ready || pending" @click="start">{{ pending ? '已有任务等待完成' : '立即备份数据库' }}</button>
      <p v-if="!data.jobs.length" class="note">还没有备份执行记录。</p>
      <div v-else class="backup-table"><table><thead><tr><th>创建时间</th><th>触发方式</th><th>结果</th><th>归档大小</th><th>说明</th></tr></thead>
        <tbody><tr v-for="job in data.jobs" :key="job.id"><td>{{ date(job.createdAt) }}</td><td>{{ job.triggerKind === 'MANUAL' ? '管理员手动' : '定时' }}</td><td>{{ backupStatus(job) }}</td><td>{{ job.encryptedBytes == null ? '—' : `${Math.ceil(job.encryptedBytes / 1024)} KiB` }}</td><td>{{ backupFailure(job.errorCode) }}<small v-if="job.encryptionKeyId">密钥标识：{{ job.encryptionKeyId }}</small></td></tr></tbody></table></div>
    </template><p v-else-if="!error" class="note">正在读取备份配置和记录…</p>
  </section>
</template>
<style scoped>
.backup-table { max-width: 100%; overflow-x: auto; margin-top: 18px }
table { width: 100%; min-width: 650px; border-collapse: collapse; text-align: left }
th, td { padding: 12px; border-bottom: 1px solid #dfe4da; vertical-align: top; overflow-wrap: anywhere }
small { display: block; color: #64736a; margin-top: 6px }
h3 { margin: 0 }
</style>
