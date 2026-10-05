package com.languagelean.dictionary;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 版本化词条内容契约；列表顺序即展示顺序，第一项为主要读音或词义。 */
public record DictionaryContent(int schemaVersion, List<Reading> readings, List<Sense> senses,
                                String sourceName, String license) {
    public DictionaryContent { sourceName = sourceName == null ? "" : sourceName; license = license == null ? "" : license; }
    /** 读音与 TTS 输入分开，未填写发音文本时不自动用假名代替。 */
    public record Reading(UUID id, String reading, String pronunciationText) {
        public Reading { reading = reading == null ? "" : reading; pronunciationText = pronunciationText == null ? "" : pronunciationText; }
    }
    /** 同一词义的译文按语言保存；旧版未标注语言时保留为空，不猜测用户母语。 */
    public record Sense(UUID id, String partOfSpeech, String gloss, List<Example> examples,
                        String glossLanguage, Map<String, Translation> translations) {
        public Sense { partOfSpeech = partOfSpeech == null ? "" : partOfSpeech; gloss = gloss == null ? "" : gloss; glossLanguage = glossLanguage == null ? "" : glossLanguage; translations = translations == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(translations)); }
        public Sense(UUID id, String partOfSpeech, String gloss, List<Example> examples) { this(id, partOfSpeech, gloss, examples, "", Map.of()); }
    }
    /** 例句原文和发音始终属于学习语言；译文及来源独立保存，不混用词典许可。 */
    public record Example(UUID id, String text, String pronunciationText, String translation,
                          String translationLanguage, Map<String, Translation> translations, Attribution attribution) {
        public Example { text = text == null ? "" : text; pronunciationText = pronunciationText == null ? "" : pronunciationText; translation = translation == null ? "" : translation; translationLanguage = translationLanguage == null ? "" : translationLanguage; translations = translations == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(translations)); }
        public Example(UUID id, String text, String pronunciationText, String translation) { this(id, text, pronunciationText, translation, "", Map.of(), null); }
    }
    /** 每份译文保留来源和许可，导入和历史快照均不能丢失这些信息。 */
    public record Translation(String text, String sourceName, String license, String sourceUrl, String author) {
        public Translation(String text, String sourceName, String license, String sourceUrl) { this(text, sourceName, license, sourceUrl, ""); }
    }
    /** 外部例句可追溯到原始句子，作者信息可留空，不编造作者。 */
    public record Attribution(String sourceName, String license, String sourceUrl, String sourceId, String author) {}
}
