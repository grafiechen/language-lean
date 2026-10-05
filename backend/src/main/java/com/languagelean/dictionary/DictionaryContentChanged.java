package com.languagelean.dictionary;
import java.util.UUID;
/** 保存成功后再异步补齐音频，不让云服务故障回滚已经保存的词条。 */
public record DictionaryContentChanged(UUID entryId, boolean draft) {}
