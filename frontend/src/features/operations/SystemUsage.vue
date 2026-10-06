<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getJson } from '../../shared/api'
import { utcMonth, type SystemUsage } from './usage'
import DatabaseBackups from './DatabaseBackups.vue'
const backups = ref<InstanceType<typeof DatabaseBackups> | null>(null)
const data = ref<SystemUsage | null>(null), month = ref(utcMonth()), busy = ref(false), error = ref('')
let sequence = 0
/** 离开或切换账号后旧请求不得写回页面。 */
async function load() {
  const request = ++sequence; busy.value = true; error.value = ''
  try {
    const value = await getJson<SystemUsage>('/api/v1/admin/system/usage?' + new URLSearchParams({ month: month.value }))
    if (request === sequence) { data.value = value; void backups.value?.load() }
  } catch (cause) { if (request === sequence) error.value = cause instanceof Error ? cause.message : '用量加载失败，请重试。' }
  finally { if (request === sequence) busy.value = false }
}
function suspend() { sequence++; busy.value = false; backups.value?.suspend(); data.value = null }
onMounted(load); onBeforeUnmount(suspend); defineExpose({ load, suspend })
const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const generated = computed(() => data.value ? new Date(data.value.generatedAt).toLocaleString('zh-CN') : '')
const metrics = computed(() => data.value ? [
  ['生成请求', data.value.generation.requests], ['请求文本字符', data.value.generation.inputCharacters],
  ['合成返回', data.value.generation.returned], ['返回音频字节', data.value.generation.responseBytes],
  ['版本切换成功', data.value.generation.ready], ['生成或上传失败', data.value.generation.failed],
  ['内容变化等弃用', data.value.generation.discarded], ['结果未确认', data.value.generation.unconfirmed],
] as const : [])
</script>
<template>
  <section aria-labelledby="system-usage-title" :aria-busy="busy">
    <div class="section-heading"><h2 id="system-usage-title">系统用量与运行配置</h2><button class="secondary" :disabled="busy" @click="load">{{ busy ? '正在刷新…' : '刷新' }}</button></div>
    <form class="usage-filter" @submit.prevent="load"><label>统计月份（UTC）<input v-model="month" type="month" min="1970-01" :max="utcMonth()" required :disabled="busy"></label><button :disabled="busy">查看</button></form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="!data && !error" class="note">正在读取服务端用量…</p>
    <template v-if="data">
      <p class="note">统计月份 {{ data.month }} · UTC自然月 · 更新于 {{ generated }}</p>
      <h3>{{ data.month }} 音频用量</h3>
      <dl class="usage-metrics"><div v-for="[label, value] in metrics" :key="label"><dt>{{ label }}</dt><dd>{{ number(value) }}</dd></div></dl>
      <p class="note">只统计接入用量记录后的合成尝试，缓存复用不计数。字符按Unicode码点计算；应用请求和返回字节不能当作服务商账单或云存储占用。结果未确认可能仍在执行，也可能因进程中断未写回。</p>
      <p v-if="data.firstRecordedRequest" class="note">最早记录：{{ new Date(data.firstRecordedRequest).toLocaleString('zh-CN') }}。</p><p v-else class="note">目前尚无音频生成用量记录。</p>
      <h3>数据与任务概览</h3>
      <dl class="usage-metrics">
        <div><dt>启用账号 / 停用账号</dt><dd>{{ number(data.accounts.active) }} / {{ number(data.accounts.disabled) }}</dd></div>
        <div><dt>公开 / 未发布 / 封禁词条</dt><dd>{{ number(data.dictionary.published) }} / {{ number(data.dictionary.draft) }} / {{ number(data.dictionary.banned) }}</dd></div>
        <div><dt>登记音频资源 / 音频版本</dt><dd>{{ number(data.audio.resources) }} / {{ number(data.audio.versions) }}</dd></div>
        <div><dt>已登记当前版本的资源</dt><dd>{{ number(data.audio.resourcesWithVersion) }}</dd></div>
        <div><dt>正在生成 / 生成失败</dt><dd>{{ number(data.audio.generating) }} / {{ number(data.audio.failed) }}</dd></div>
        <div><dt>等待清理的云文件</dt><dd>{{ number(data.audio.pendingCleanup) }}</dd></div>
        <div><dt>待审投稿 / 待处理发音反馈</dt><dd>{{ number(data.work.pendingContributions) }} / {{ number(data.work.pendingAudioFeedback) }}</dd></div>
      </dl>
      <p class="note">登记音频数量来自数据库，不代表云文件仍存在。失败资源可能仍保留可播放的旧版本，因此各项数量可能重叠。</p>
      <h3>运行配置</h3>
      <dl class="usage-config">
        <div><dt>Google TTS 调用开关</dt><dd>{{ data.configuration.googleTtsEnabled ? '已开启' : '未开启' }}</dd></div>
        <div><dt>R2 音频存储</dt><dd>{{ data.configuration.r2Configured ? '已配置' : '未配置' }}</dd></div>
        <div><dt>账号邮件 / 邮件密码重置</dt><dd>{{ data.configuration.mailConfigured ? '已配置' : '未配置' }} / {{ data.configuration.passwordRecoveryConfigured ? '已配置' : '未配置' }}</dd></div>
        <div><dt>HTTPS 会话 Cookie</dt><dd>{{ data.configuration.secureSessionCookie ? '已开启 Secure' : '开发模式：未开启 Secure' }}</dd></div>
        <div><dt>密码传输主密钥</dt><dd>{{ data.configuration.persistentPasswordMasterConfigured ? '已配置服务器主密钥' : '开发模式：进程级临时主密钥' }}</dd></div>
      </dl>
      <p class="note">配置状态不会进行云连接测试，也不证明服务已连通。密钥由服务器私有配置维护，页面不读取或显示密钥明文。</p>
      <DatabaseBackups ref="backups" />
    </template>
  </section>
</template>
<style scoped>
.usage-filter { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin-bottom: 18px }
.usage-filter label { min-width: 170px }
.usage-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr)); gap: 12px; margin: 16px 0 10px }
.usage-metrics div { border: 1px solid #dfe4da; border-radius: 10px; padding: 14px; min-width: 0 }
dt { font-size: .88rem; color: #64736a }
dd { margin: 8px 0 0; overflow-wrap: anywhere }
.usage-metrics dd { font-size: 1.35rem; font-weight: 650; font-variant-numeric: tabular-nums }
.usage-config div { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; padding: 12px 0; border-bottom: 1px solid #dfe4da }
.usage-config dd { margin: 0 }
</style>
