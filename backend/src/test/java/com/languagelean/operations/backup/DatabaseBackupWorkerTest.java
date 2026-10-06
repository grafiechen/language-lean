package com.languagelean.operations.backup;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 长时间IO只能占用专属工作线程，重复轮询不能启动多个任务。 */
class DatabaseBackupWorkerTest {
    @Test @Timeout(10)
    void slowBackupDoesNotBlockPollingOrCreateDuplicateWorkers() throws Exception {
        var runner = mock(DatabaseBackupRunner.class); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); try { release.await(); } finally { done.countDown(); } return null; }).when(runner).runOnce();
        var worker = new DatabaseBackupWorker(runner);
        try {
            worker.poll(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            worker.poll(); verify(runner, times(1)).runOnce(); release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); worker.close(); }
    }
}
