package com.languagelean.accounts;

import com.languagelean.audio.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** 只在测试 classpath 上存在的云替身；输出是测试音，绝不是生产 TTS 降级实现。 */
@TestConfiguration(proxyBeanMethods = false)
public class AudioTestDoubles {
    @Bean @Primary public TestSpeech testSpeech() { return new TestSpeech(); }
    @Bean @Primary public TestObjects testObjects() { return new TestObjects(); }

    /** 可注入等待、失败或内容变更，用于复现云任务竞态。 */
    public static class TestSpeech implements SpeechSynthesizer {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean enabled = true, fail;
        volatile Consumer<String> callback;
        @Override public boolean configured() { return enabled; }
        @Override public byte[] synthesize(TtsSettingsService.Profile profile, String text) {
            calls.incrementAndGet();
            if (callback != null) callback.accept(text);
            if (fail) throw new IllegalStateException("test synthesis failed");
            try (var input = getClass().getResourceAsStream("/audio/test-tone.mp3")) { return input.readAllBytes(); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        void reset() { calls.set(0); enabled = true; fail = false; callback = null; }
    }
    /** 内存对象存储模拟缺失、损坏和上传失败，不连接外部服务。 */
    public static class TestObjects implements AudioObjectStore {
        final AtomicInteger reads = new AtomicInteger();
        final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap<>();
        volatile boolean fail;
        @Override public boolean configured() { return true; }
        @Override public void put(String key, byte[] bytes) { if (fail) throw new IllegalStateException("test upload failed"); files.put(key, bytes.clone()); }
        @Override public byte[] get(String key) { reads.incrementAndGet(); var bytes = files.get(key); if (bytes == null) throw new IllegalStateException("test object missing"); return bytes.clone(); }
        @Override public boolean exists(String key) { reads.incrementAndGet(); return files.containsKey(key); }
        @Override public void delete(String key) { if (fail) throw new IllegalStateException("test delete failed"); files.remove(key); }
        void reset() { files.clear(); reads.set(0); fail = false; }
    }
}
