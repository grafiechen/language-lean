"""验证跨版本义项匹配和不可信外部译文的边界，避免误配到已有学习身份。"""
import copy
import unittest
from collections import Counter

from jmdict_to_postgres import stable_id
from tomoshi_to_postgres import chinese_text, sense_patches


class TomoshiMatchingTest(unittest.TestCase):
    """使用不同源版本、词性和删移义项验证导入器是否会拒绝错配。"""
    def setUp(self):
        self.sequence = '1195140'
        self.baseline = {'senses': [{'id': str(stable_id('sense', self.sequence)),
                                    'gloss': 'to bully; to tease', 'partOfSpeech': 'Ichidan verb; transitive verb'}]}
        self.incoming = {'id': self.sequence, 'senses': [{'pos': ['Ichidan verb', 'transitive verb'],
            'glosses': [{'text': 'to bully', 'lang': 'eng'}, {'text': 'to tease', 'lang': 'eng'},
                        {'text': '未经核验的内嵌中文', 'lang': 'zho'}]}]}
        self.simplified = {'senses': {'0': {'glosses': [{'text': '欺负；嘲弄'}], 'examples': {'0': '孤立例句译文'}}}}
        self.traditional = {'senses': {'0': {'glosses': [{'text': '欺負；嘲弄'}]}}}

    def patch(self):
        self.counts = Counter()
        return sense_patches(self.sequence, self.baseline, self.incoming, self.simplified, self.traditional, self.counts)

    def test_locales_and_provenance_without_orphan_examples(self):
        before = copy.deepcopy(self.baseline)
        translations = self.patch()[self.baseline['senses'][0]['id']]
        self.assertEqual(translations['zh-Hans']['text'], '欺负；嘲弄')
        self.assertEqual(translations['zh-Hant']['text'], '欺負；嘲弄')
        self.assertIn('机器翻译', translations['zh-Hans']['sourceName'])
        self.assertEqual(translations['zh-Hans']['author'], 'Y1Z')
        self.assertNotIn('examples', translations['zh-Hans'])
        self.assertEqual(self.baseline, before)

    def test_changed_english_is_skipped(self):
        self.incoming['senses'][0]['glosses'][0]['text'] = 'to pick on'
        self.assertEqual(self.patch(), {})
        self.assertEqual(self.counts['changedSourceSense'], 1)

    def test_changed_pos_is_skipped(self):
        self.incoming['senses'][0]['pos'] = ['noun']
        self.assertEqual(self.patch(), {})

    def test_moved_sense_does_not_attach_chinese_by_index(self):
        self.incoming['senses'].insert(0, {'pos': ['noun'], 'glosses': [{'lang': 'eng', 'text': 'bullying'}]})
        self.assertEqual(self.patch(), {})

    def test_same_spelling_with_different_source_id_is_rejected(self):
        self.incoming['id'] = '9999999'
        self.assertEqual(self.patch(), {})

    def test_absent_sense_is_skipped(self):
        self.incoming['senses'] = []
        self.assertEqual(self.patch(), {})

    def test_missing_one_locale_does_not_discard_other_locale(self):
        self.traditional = {}
        translations = self.patch()[self.baseline['senses'][0]['id']]
        self.assertEqual(set(translations), {'zh-Hans'})

    def test_inherited_pos_and_nonzero_original_sense_index(self):
        self.incoming['senses'].append({'pos': [], 'glosses': [{'lang': 'eng', 'text': 'to treat harshly'}]})
        identifier = str(stable_id('sense', self.sequence, 1))
        self.baseline['senses'] = [{'id': identifier, 'gloss': 'to treat harshly', 'partOfSpeech': 'Ichidan verb; transitive verb'}]
        self.simplified['senses']['1'] = {'glosses': [{'text': '苛待'}]}
        self.assertEqual(self.patch()[identifier]['zh-Hans']['text'], '苛待')

    def test_bad_text_is_rejected_without_truncation(self):
        for text in ['english only', '坏\ufffd字', '控制\x00字', '中' * 4001, '']:
            with self.subTest(text=text[:20]):
                self.assertIsNone(chinese_text({'glosses': [{'text': text}]}))
        self.assertEqual(chinese_text({'glosses': [{'text': '猫'}, {'text': '猫'}]}), '猫')


if __name__ == '__main__':
    unittest.main()
