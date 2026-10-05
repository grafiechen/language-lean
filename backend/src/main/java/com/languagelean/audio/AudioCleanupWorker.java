package com.languagelean.audio;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
/** 每分钟限量重试已删除私人音频，重启后继续处理持久任务。 */
@Component @EnableScheduling
@ConditionalOnProperty(name = "app.audio.cleanup-enabled", havingValue = "true", matchIfMissing = true)
class AudioCleanupWorker {
    private final AudioCleanupService cleanup;
    AudioCleanupWorker(AudioCleanupService cleanup) { this.cleanup = cleanup; }
    /** 先等待启动完成，再执行幂等云删除；失败留待下次。 */
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    void retry() { cleanup.drain(); }
}
