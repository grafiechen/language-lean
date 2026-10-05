<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getJson, postJson } from '../../shared/api'
import type { TtsSetting } from './types'
const data = ref<{ languages: TtsSetting[]; availability: { googleEnabled: boolean; storageConfigured: boolean } } | null>(null)
const selected = ref<TtsSetting | null>(null), busy = ref(false), error = ref(''), message = ref('')
const voices = computed(() => selected.value ? [...new Set([selected.value.voice,
  ...['Aoede', 'Kore', 'Leda', 'Charon', 'Zephyr'].map(name => selected.value!.locale + '-Chirp3-HD-' + name)])].filter(Boolean) : [])
/** 返回服务器配置状态，不读取或编辑服务密钥。 */
async function load() {
  try { data.value = await getJson('/api/v1/admin/audio/settings') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'TTS 配置加载失败' }
}
/** 表单使用副本，取消编辑不污染列表。 */
function edit(item: TtsSetting) { selected.value = { ...item, voice: item.voice || item.locale + '-Chirp3-HD-Aoede' }; error.value = ''; message.value = '' }
/** 保存版本化语言设置。 */
async function save() {
  if (!selected.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { selected.value = await postJson('/api/v1/admin/audio/settings/' + selected.value.languageCode, selected.value); await load(); message.value = 'TTS 配置已保存。' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'TTS 配置保存失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <section>
    <h2>TTS 配置</h2>
    <p v-if="data" class="note">Google 调用：{{ data.availability.googleEnabled ? '已启用' : '尚未启用' }} · R2 存储：{{ data.availability.storageConfigured ? '已配置' : '尚未配置' }}</p>
    <p class="note">保存内容、打开详情和点击发音都会检查并补齐缺失音频。云服务凭据由服务端配置。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="feedback" role="status">{{ message }}</p>
    <div class="entry-list"><button v-for="item in data?.languages" :key="item.languageCode" class="entry-row" @click="edit(item)"><strong>{{ item.displayName }}</strong><span>{{ item.voice || '未配置声音' }} · {{ item.enabled ? '已启用' : '已关闭' }}</span></button></div>
    <form v-if="selected" class="editor-fields" @submit.prevent="save"><fieldset :disabled="busy">
      <legend>{{ selected.displayName }} · 声音设置</legend>
      <div class="form-grid"><label>服务商<select v-model="selected.provider"><option value="GOOGLE">Google Cloud TTS</option></select></label><label>模型<select v-model="selected.model"><option value="Chirp3-HD">Chirp 3 HD</option></select></label></div>
      <label>默认声音<select v-model="selected.voice" required><option v-for="voice in voices" :key="voice" :value="voice">{{ voice }}</option></select></label>
      <label class="check-label"><input v-model="selected.enabled" type="checkbox">启用此语言的音频生成</label>
      <label class="check-label"><input v-model="selected.personalAutoGenerate" type="checkbox">允许个人音频生成</label>
      <p class="note">包括私有词条、个人覆盖的保存、详情和点击补生成。关闭后不生成新个人音频，已有正确版本仍可播放。</p>
      <button>保存配置</button><button type="button" class="quiet" @click="selected = null">取消</button>
    </fieldset></form>
  </section>
</template>
