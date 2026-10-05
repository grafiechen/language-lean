package com.languagelean.learning;
import java.util.UUID;
/** 个人读音/例句成功提交后的生成通知，不携带私人正文。 */
public record PersonalAudioChanged(UUID learningItemId, UUID userId) {}
