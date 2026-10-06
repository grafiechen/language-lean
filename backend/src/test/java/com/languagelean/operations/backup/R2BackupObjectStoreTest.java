package com.languagelean.operations.backup;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 通过真实上传/回读核验适配器验证损坏、截断和超长对象，完全不连接云服务。 */
class R2BackupObjectStoreTest {
    @TempDir Path directory;
    private ResponseInputStream<GetObjectResponse> response(byte[] bytes) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) bytes.length).build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }
    @Test void uploadRequiresFullReadbackMatchingSizeAndChecksum() throws Exception {
        var config = new DatabaseBackupConfiguration(); config.setR2Bucket("test-backups");
        var client = mock(S3Client.class); var store = new R2BackupObjectStore(config, client);
        var file = directory.resolve("test.llbackup"); var bytes = new byte[130000]; new java.security.SecureRandom().nextBytes(bytes); Files.write(file, bytes);
        var hash = BackupArchive.sha256(file);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(bytes));
        store.uploadAndVerify("database-backups/test", file, hash);
        verify(client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
        var corrupted = bytes.clone(); corrupted[100] ^= 1;
        for (var wrong : new byte[][]{corrupted, Arrays.copyOf(bytes, bytes.length - 1), Arrays.copyOf(bytes, bytes.length + 1)}) {
            when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(wrong));
            assertThrows(IOException.class, () -> store.uploadAndVerify("database-backups/test", file, hash));
        }
    }
    @Test void storageScopeIsDistinctAndNeverReturnsCredentialsOrBucketName() {
        var config = new DatabaseBackupConfiguration(); config.setR2AccountId("0".repeat(32)); config.setR2Bucket("first");
        var first = new R2BackupObjectStore(config, mock(S3Client.class)); config.setR2Bucket("second");
        var second = new R2BackupObjectStore(config, mock(S3Client.class)); assertNotEquals(first.scopeId(), second.scopeId()); assertEquals(64, first.scopeId().length());
        assertFalse(first.scopeId().contains("first"));
    }
}
