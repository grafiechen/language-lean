package com.languagelean.audio;
import com.languagelean.dictionary.DictionaryContentChanged;
import com.languagelean.learning.PrivateEntryChanged;
import com.languagelean.learning.PersonalAudioChanged;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/** 事务提交后异步生成保存内容，失败留给详情或点击播放补齐。 */
@Component
class AudioSavedListener implements AutoCloseable {
    private final AudioService audio;
    private final boolean enabled;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AudioSavedListener.class);
    /** 有界线程池限制同时的云请求；默认测试可关闭自动保存生成。 */
    AudioSavedListener(AudioService audio, @Value("${app.audio.auto-save-enabled:true}") boolean enabled) {
        this.audio = audio; this.enabled = enabled;
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("audio-save-"); executor.initialize();
    }
    /** 保存已经提交才入队，队列满不改变保存成功结果。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void saved(DictionaryContentChanged event) {
        if (!enabled || !audio.availability().googleEnabled() || !audio.availability().storageConfigured()) return;
        try {
            executor.execute(() -> {
                try { audio.generateSaved(event.entryId(), event.draft()); }
                catch (RuntimeException failed) { log.warn("词条 {} 的自动音频准备未完成，可在详情页重试", event.entryId()); }
            });
        } catch (RejectedExecutionException full) { log.warn("自动音频队列已满，词条 {} 可在详情页补齐", event.entryId()); }
    }
    /** 私有保存仍使用同一有界线程池，事件身份在生成时再次校验。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void savedPrivate(PrivateEntryChanged event) {
        if (!enabled || !audio.availability().googleEnabled() || !audio.availability().storageConfigured()) return;
        try {
            executor.execute(() -> { try { audio.generatePersonalSaved(event.entryId(), event.userId()); }
                catch (RuntimeException failed) { log.warn("个人词条 {} 的自动音频准备未完成，可在详情页重试", event.entryId()); } });
        } catch (RejectedExecutionException full) { log.warn("自动音频队列已满，个人词条 {} 可在详情页补齐", event.entryId()); }
    }
    /** 本人覆盖资源仍在提交后生成，日志只记录身份不记录正文。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void savedOverride(PersonalAudioChanged event) {
        if (!enabled || !audio.availability().googleEnabled() || !audio.availability().storageConfigured()) return;
        try {
            executor.execute(() -> { try { audio.generateOverrideSaved(event.learningItemId(), event.userId()); }
                catch (RuntimeException failed) { log.warn("学习条目 {} 的个人音频准备未完成，可在详情页重试", event.learningItemId()); } });
        } catch (RejectedExecutionException full) { log.warn("自动音频队列已满，学习条目 {} 可在详情页补齐", event.learningItemId()); }
    }
    /** 关闭本服务创建的工作线程。 */
    @Override public void close() { executor.shutdown(); }
}
