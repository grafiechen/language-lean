<script setup lang="ts">
import type { Translation } from './types'
defineProps<{ credit?: Translation }>()
</script>
<template><p v-if="credit" class="field-note">译文来源：<a v-if="credit.sourceUrl?.startsWith('https://')" :href="credit.sourceUrl" target="_blank" rel="noreferrer">{{ credit.sourceName }}</a><span v-else>{{ credit.sourceName }}</span><span v-if="credit.license"> · {{ credit.license }}</span><span v-if="credit.author"> · {{ credit.author }}</span></p></template>
