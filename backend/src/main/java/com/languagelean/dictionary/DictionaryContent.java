package com.languagelean.dictionary;

import java.util.List;
import java.util.UUID;

/** 版本化词条内容契约；列表顺序即展示顺序，第一项为主要读音或词义。 */
public record DictionaryContent(int schemaVersion, List<Reading> readings, List<Sense> senses,
                                String sourceName, String license) {
    /** 读音与 TTS 输入分开，未填写发音文本时不自动用假名代替。 */
    public record Reading(UUID id, String reading, String pronunciationText) {}
    /** 词性及中文释义；例句仅挂在当前词义下。 */
    public record Sense(UUID id, String partOfSpeech, String gloss, List<Example> examples) {}
    /** 独立例句身份，发音文本允许留空，供后续 TTS 资产关联。 */
    public record Example(UUID id, String text, String pronunciationText, String translation) {}
}
