package com.languagelean.audio;

import jakarta.persistence.*;

/** 持久化每种语言的声音和生成开关，不存放任何云凭据。 */
@Entity @Table(name = "tts_language_setting")
class TtsLanguageSetting {
    @Id @Column(name = "language_code", length = 16) String languageCode;
    @Column(nullable = false, length = 32) String provider = "GOOGLE";
    @Column(nullable = false, length = 32) String model = "Chirp3-HD";
    @Column(nullable = false, length = 120) String voice;
    @Column(nullable = false) boolean enabled = true;
    @Column(name = "personal_auto_generate", nullable = false) boolean personalAutoGenerate = true;
    @Version long version;
    /** 供 JPA 恢复配置。 */
    protected TtsLanguageSetting() {}
}
