package com.languagelean.audio;

/** 服务端语音合成端口；测试替身不进入生产配置。 */
public interface SpeechSynthesizer {
    /** 是否已显式启用云调用。 */
    boolean configured();
    /** 仅使用明确填写的发音文本，返回 MP3 内容。 */
    byte[] synthesize(TtsSettingsService.Profile profile, String text);
}
