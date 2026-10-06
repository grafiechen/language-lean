package com.languagelean.operations.backup;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实加密格式与认证边界，不把未认证数据交给恢复工具。 */
class BackupArchiveTest {
    @TempDir Path directory;
    private String key() { var bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
    private byte[] seal(byte[] content, String key) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var encrypted = BackupArchive.encrypt(output, "test-key-v1", key)) { encrypted.write(content); }
        return output.toByteArray();
    }
    private void rejects(byte[] archive, String key) throws Exception {
        var input = directory.resolve("invalid.llbackup"); Files.write(input, archive);
        var output = directory.resolve("invalid.pgdump");
        assertThrows(IOException.class, () -> BackupArchive.decrypt(input, output, "test-key-v1", key));
        assertFalse(Files.exists(output));
        try (var paths = Files.list(directory)) { assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("unsealed-"))); }
    }
    @Test void largeAndEmptyArchivesRoundTripWithoutPlaintextOrDeterministicCiphertexts() throws Exception {
        var key = key(); var content = new byte[200000]; new SecureRandom().nextBytes(content);
        var encrypted = seal(content, key); assertFalse(Arrays.equals(encrypted, seal(content, key)));
        var input = directory.resolve("sample.llbackup"); var output = directory.resolve("sample.pgdump");
        Files.write(input, encrypted); BackupArchive.decrypt(input, output, "test-key-v1", key); assertArrayEquals(content, Files.readAllBytes(output));
        Files.write(input, seal(new byte[0], key)); var empty = directory.resolve("empty.pgdump");
        BackupArchive.decrypt(input, empty, "test-key-v1", key); assertEquals(0, Files.size(empty));
    }
    @Test void wrongKeyTamperingTruncationAndTrailingBytesNeverPublishOutput() throws Exception {
        var key = key(); var archive = seal("PGDMP-private-learning-content".getBytes(java.nio.charset.StandardCharsets.UTF_8), key);
        rejects(archive, key());
        var corrupted = archive.clone(); corrupted[corrupted.length - 1] ^= 1; rejects(corrupted, key);
        rejects(Arrays.copyOf(archive, archive.length - 1), key);
        rejects(Arrays.copyOf(archive, archive.length - 20), key);
        rejects(Arrays.copyOf(archive, archive.length + 1), key);
        var header = archive.clone(); header[0] ^= 1; rejects(header, key);
    }
    @Test void reorderedDuplicateAndMissingBlocksAreRejected() throws Exception {
        var key = key(); var archive = seal(new byte[150000], key);
        int headerSize = ByteBuffer.wrap(archive, 8, 4).getInt(); int start = 12 + headerSize, frameSize = 4 + 65536 + 16;
        var reordered = archive.clone();
        System.arraycopy(archive, start + frameSize, reordered, start, frameSize);
        System.arraycopy(archive, start, reordered, start + frameSize, frameSize); rejects(reordered, key);
        var duplicated = archive.clone(); System.arraycopy(archive, start, duplicated, start + frameSize, frameSize); rejects(duplicated, key);
        var missing = new byte[archive.length - frameSize]; System.arraycopy(archive, 0, missing, 0, start);
        System.arraycopy(archive, start + frameSize, missing, start, archive.length - start - frameSize); rejects(missing, key);
    }
    @Test void authenticatedHeaderAndExistingDestinationAreProtected() throws Exception {
        var key = key(); var input = directory.resolve("input.llbackup"); Files.write(input, seal(new byte[100], key));
        var output = directory.resolve("existing.pgdump"); Files.writeString(output, "keep");
        assertThrows(IOException.class, () -> BackupArchive.decrypt(input, output, "test-key-v1", key)); assertEquals("keep", Files.readString(output));
        assertThrows(IOException.class, () -> BackupArchive.decrypt(input, directory.resolve("wrong.pgdump"), "wrong-id", key));
        var bytes = Files.readAllBytes(input); var headerSize = ByteBuffer.wrap(bytes, 8, 4).getInt();
        var header = new String(bytes, 12, headerSize, java.nio.charset.StandardCharsets.UTF_8); var changed = header.replace("test-key-v1", "test-key-v2");
        System.arraycopy(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, bytes, 12, headerSize); Files.write(input, bytes);
        assertThrows(IOException.class, () -> BackupArchive.decrypt(input, directory.resolve("header.pgdump"), "test-key-v2", key));
    }
}
