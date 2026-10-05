import { expect, it } from 'vitest'
import { localize } from './translations'
it('selects the preferred translation without changing or discarding source text', () => {
  const source = { 'zh-Hans': { text: '猫', sourceName: '手工录入', license: '' } }
  expect(localize('cat', 'en', source, 'zh-Hans', 'JMdict').text).toBe('猫')
  expect(localize('cat', 'en', source, 'en', 'JMdict').fallback).toBe(false)
  expect(source['zh-Hans'].text).toBe('猫')
})
it('uses a neutral Chinese translation for either script preference without inventing a script conversion', () => {
  const value = { zh: { text: '貓', sourceName: 'Tatoeba', license: 'CC BY 2.0 FR' } }
  expect(localize('cat', 'en', value, 'zh-Hans', 'JMdict')).toMatchObject({ text: '貓', language: 'zh', fallback: false })
  expect(localize('cat', 'en', value, 'zh-Hant', 'JMdict')).toMatchObject({ text: '貓', language: 'zh', fallback: false })
})
it('marks fallback language including old JMdict snapshots and unlabelled personal content', () => {
  expect(localize('cat', undefined, undefined, 'zh-Hans', 'JMdict 2026')).toMatchObject({ text: 'cat', language: 'en', fallback: true })
  expect(localize('bonjour', undefined, undefined, 'zh-Hans', '个人')).toMatchObject({ language: '', fallback: true })
  expect(localize('', '', {}, 'en', '')).toMatchObject({ text: '', fallback: false })
})
