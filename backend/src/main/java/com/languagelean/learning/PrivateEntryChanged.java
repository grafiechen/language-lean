package com.languagelean.learning;
import java.util.UUID;
/** 仅在保存提交后触发个人音频准备，事件不包含私人正文。 */
public record PrivateEntryChanged(UUID entryId, UUID userId) {}
