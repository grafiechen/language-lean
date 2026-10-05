package com.languagelean.audio;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 独立短事务记录云边界，不让长时间合成持有数据库连接或行锁。 */
@Service
class AudioUsageRecorder {
    private final AudioGenerationUsageRepository rows;
    AudioUsageRecorder(AudioGenerationUsageRepository rows) { this.rows = rows; }
    /** 开始记录失败时不进入可能收费的合成调用；复用及未认领成功的请求不调用此方法。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID begin(TtsSettingsService.Profile profile, int characters) { return rows.saveAndFlush(AudioGenerationUsage.begin(profile, characters)).id; }
    /** 最终记录失败保持未确认，不能把已生成的音频回滚或错误报告为生成失败。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finish(UUID id, String outcome, Long bytes) {
        var row = rows.findById(id).orElseThrow();
        row.outcome = outcome; row.responseBytes = bytes; row.completedAt = Instant.now(); rows.flush();
    }
    static void unconfirmed() { LoggerFactory.getLogger(AudioUsageRecorder.class).warn("音频用量结果暂未确认；已完成的音频状态保持不变"); }
}
