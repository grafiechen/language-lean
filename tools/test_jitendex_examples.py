"""验证例句导入对原句身份、注音、中文冲突及已有译文的处理。"""
import copy
import unittest
from collections import Counter

from jitendex_examples import eligible_examples, match_translations, plain_text, row_examples, valid_chinese


def fixture_row():
    """生成带注音和精确 Tatoeba 来源的小型结构化例句。"""
    node = {'tag': 'div', 'data': {'content': 'example-sentence', 'source': '153660', 'source-type': 'tat'},
            'content': [
                {'tag': 'div', 'data': {'content': 'example-sentence-a'}, 'content': {'tag': 'span', 'lang': 'ja',
                 'content': [{'tag': 'ruby', 'content': ['私', {'tag': 'rt', 'content': 'わたし'}]}, 'は彼をいじめたことを後悔した。']}},
                {'tag': 'div', 'data': {'content': 'example-sentence-b'}, 'content': {'tag': 'span', 'lang': 'zh', 'content': '我后悔欺负了他。'}}]}
    return ['苛める', 'いじめる', '', '', 200, [{'type': 'structured-content', 'content': node}], 1195140, '']


class JitendexExamplesTest(unittest.TestCase):
    def test_ruby_does_not_change_original_japanese(self):
        self.assertEqual(row_examples(fixture_row()), [('153660', '私は彼をいじめたことを後悔した。', '我后悔欺负了他。')])

    def test_cross_reference_examples_are_ignored(self):
        row = fixture_row()
        row[5][0]['content'] = {'tag': 'div', 'data': {'content': 'xref'}, 'content': row[5][0]['content']}
        self.assertEqual(row_examples(row), [])

    def test_missing_source_or_wrong_source_type_are_rejected(self):
        for change in [{'source': 'unknown'}, {'source-type': 'other'}]:
            row = fixture_row(); row[5][0]['content']['data'].update(change)
            self.assertEqual(row_examples(row), [])

    def test_only_explicit_japanese_and_chinese_are_accepted(self):
        for index, language in [(0, 'en'), (1, 'en'), (1, 'zh-Hant')]:
            row = fixture_row(); row[5][0]['content']['content'][index]['content']['lang'] = language
            self.assertEqual(row_examples(row), [])

    def test_chinese_human_translation_is_preserved(self):
        for language in ['zh', 'zh-Hans', 'zh-Hant']:
            content = {'senses': [{'examples': [{'id': 'example', 'text': '猫です。', 'translation': 'It is a cat.',
                'translationLanguage': 'en', 'translations': {language: {'text': '这是猫。'}},
                'attribution': {'sourceId': '123', 'sourceUrl': 'https://tatoeba.org/en/sentences/show/123'}}]}]}
            before = copy.deepcopy(content); counts = Counter()
            self.assertEqual(eligible_examples(content, counts), {})
            self.assertEqual(counts['existingChinesePreserved'], 1)
            self.assertEqual(content, before)

    def test_source_id_must_agree_with_original_url(self):
        content = {'senses': [{'examples': [{'id': 'example', 'text': '猫です。', 'translation': 'It is a cat.',
            'translationLanguage': 'en', 'translations': {},
            'attribution': {'sourceId': '123', 'sourceUrl': 'https://tatoeba.org/en/sentences/show/999'}}]}]}
        self.assertEqual(eligible_examples(content, Counter()), {})

    def test_conflicting_variants_are_not_chosen_arbitrarily(self):
        counts = Counter(); key = ('123', '猫です。')
        self.assertEqual(match_translations({'example': key}, {key: {'是猫。', '这是猫。'}}, counts), {})
        self.assertEqual(counts['conflictingTranslationSkipped'], 1)

    def test_exact_sentence_and_source_id_required(self):
        expected = {'example': ('123', '猫です。')}
        for candidates in [{('999', '猫です。'): {'是猫。'}}, {('123', '猫だった。'): {'是猫。'}}]:
            self.assertEqual(match_translations(expected, candidates, Counter()), {})

    def test_matched_translation_has_independent_provenance(self):
        key = ('123', '猫です。')
        patch = match_translations({'example': key}, {key: {'是猫。'}}, Counter())
        self.assertEqual(patch['example']['zh-Hans']['license'], 'CC BY-SA 4.0')
        self.assertIn('机器翻译', patch['example']['zh-Hans']['sourceName'])
        self.assertIn('greyindex', patch['example']['zh-Hans']['author'])

    def test_untranslated_and_damaged_text_rejected(self):
        for text in ['English only', '', '坏\ufffd字', '坏\x00字', '中' * 2001, '中' * 1999 + '😀']:
            self.assertFalse(valid_chinese(text))

    def test_markup_does_not_become_html_or_furigana(self):
        self.assertEqual(plain_text([{'tag': 'ruby', 'content': ['猫', {'tag': 'rt', 'content': 'ねこ'}]}, {'tag': 'img', 'src': 'https://example.com'}]), '猫')

    def test_attribution_footnotes_do_not_enter_translation_text(self):
        row = fixture_row(); b = row[5][0]['content']['content'][1]
        b['content'] = [b['content'], {'tag': 'span', 'data': {'content': 'attribution-footnote'}, 'content': '[1]'}]
        self.assertEqual(row_examples(row)[0][2], '我后悔欺负了他。')

    def test_chinese_with_image_glyph_is_not_silently_truncated(self):
        row = fixture_row(); b = row[5][0]['content']['content'][1]
        b['content'] = [b['content'], {'tag': 'img', 'path': 'glyph.png'}]
        self.assertEqual(row_examples(row), [])


if __name__ == '__main__':
    unittest.main()
