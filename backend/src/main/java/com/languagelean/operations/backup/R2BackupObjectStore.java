package com.languagelean.operations.backup;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** 独立私有R2桶，仅传输加密归档，完整回读核对大小和SHA256后才确认成功。 */
@Component
class R2BackupObjectStore implements BackupObjectStore, AutoCloseable {
    private final String bucket, scope;
    private final S3Client client;
    @Autowired R2BackupObjectStore(DatabaseBackupConfiguration config) { this(config, client(config)); }
    /** SDK边界可以注入测试客户端，生产固定使用Cloudflare官方账户域名。 */
    R2BackupObjectStore(DatabaseBackupConfiguration config, S3Client client) {
        bucket = config.getR2Bucket(); var account = config.getR2AccountId();
        try { scope = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((account + ":" + bucket).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        this.client = client;
    }
    private static S3Client client(DatabaseBackupConfiguration config) {
        var account = config.getR2AccountId(); var bucket = config.getR2Bucket();
        return account.matches("[a-fA-F0-9]{32}") && !config.getR2AccessKeyId().isBlank() && !config.getR2SecretAccessKey().isBlank() && !bucket.isBlank()
            ? S3Client.builder().endpointOverride(URI.create("https://" + account + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto")).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getR2AccessKeyId(), config.getR2SecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofMinutes(5)).apiCallAttemptTimeout(Duration.ofMinutes(3)))
                .build() : null;
    }
    public boolean configured() { return client != null; }
    public String scopeId() { return scope; }
    public void uploadAndVerify(String key, Path encrypted, String sha256) throws Exception {
        var expected = Files.size(encrypted); var connection = requireClient();
        connection.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/octet-stream").build(), RequestBody.fromFile(encrypted));
        try (var input = connection.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            var digest = MessageDigest.getInstance("SHA-256"); var buffer = new byte[65536]; long actual = 0;
            var deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos(); int read;
            while ((read = input.read(buffer)) != -1) {
                if (System.nanoTime() > deadline || actual > expected - read) { input.abort(); throw new java.io.IOException("BACKUP_READBACK_FAILED"); }
                digest.update(buffer, 0, read); actual += read;
            }
            if (actual != expected || !sha256.equals(HexFormat.of().formatHex(digest.digest()))) throw new java.io.IOException("BACKUP_READBACK_FAILED");
        }
    }
    public void delete(String key) { requireClient().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()); }
    private S3Client requireClient() { if (client == null) throw new IllegalStateException("BACKUP_STORAGE_NOT_CONFIGURED"); return client; }
    public void close() { if (client != null) client.close(); }
}
