package com.languagelean.audio;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 匿名合成尝试，保留重试及上传失败的真实应用用量，不引用用户或发音正文。 */
@Entity @Table(name = "audio_generation_usage")
class AudioGenerationUsage {
    @Id UUID id;
    @Column(name = "requested_at", nullable = false) Instant requestedAt;
    @Column(name = "completed_at") Instant completedAt;
    @Column(nullable = false, length = 32) String provider;
    @Column(nullable = false, length = 32) String model;
    @Column(name = "input_characters", nullable = false) int inputCharacters;
    @Column(name = "response_bytes") Long responseBytes;
    @Column(nullable = false, length = 16) String outcome;
    protected AudioGenerationUsage() {}
    static AudioGenerationUsage begin(TtsSettingsService.Profile profile, int characters) {
        var row = new AudioGenerationUsage(); row.id = UUID.randomUUID(); row.requestedAt = Instant.now();
        row.provider = profile.provider(); row.model = profile.model(); row.inputCharacters = characters; row.outcome = "REQUESTED";
        return row;
    }
}
