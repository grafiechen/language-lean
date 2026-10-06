package com.languagelean.operations.backup;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 单独IO工作线程，不能占用系统调度线程而延迟一次性密码密钥的过期清理。 */
@Component @ConditionalOnProperty(name = "app.backup.enabled", havingValue = "true")
class DatabaseBackupWorker {
    private final DatabaseBackupRunner runner;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    DatabaseBackupWorker(DatabaseBackupRunner runner) { this.runner = runner; }
    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    void poll() {
        if (!running.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try { runner.runOnce(); }
            catch (RuntimeException failed) { LoggerFactory.getLogger(DatabaseBackupWorker.class).warn("服务器备份任务暂未完成，请查看执行记录"); }
            finally { running.set(false); }
        });
    }
    @PreDestroy void close() throws InterruptedException { executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS); }
}
