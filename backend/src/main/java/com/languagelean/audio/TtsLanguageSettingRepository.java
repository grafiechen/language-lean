package com.languagelean.audio;
import org.springframework.data.jpa.repository.JpaRepository;
/** 语言 TTS 设置的 ORM 仓储。 */
interface TtsLanguageSettingRepository extends JpaRepository<TtsLanguageSetting, String> {}
