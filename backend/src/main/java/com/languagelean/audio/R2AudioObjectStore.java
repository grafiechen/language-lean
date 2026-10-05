package com.languagelean.audio;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** Cloudflare R2 私有对象适配器；播放统一经过服务器权限校验。 */
@Component
class R2AudioObjectStore implements AudioObjectStore, AutoCloseable {
    private final String bucket;
    private final S3Client client;
    /** 只使用固定 R2 账户域名；配置不完整时禁用，避免应用启动依赖外部网络。 */
    R2AudioObjectStore(@Value("${app.audio.r2-account-id:}") String account,
                      @Value("${app.audio.r2-access-key-id:}") String access,
                      @Value("${app.audio.r2-secret-access-key:}") String secret,
                      @Value("${app.audio.r2-bucket:}") String bucket) {
        this.bucket = bucket;
        client = account.matches("[a-fA-F0-9]{32}") && !access.isBlank() && !secret.isBlank() && !bucket.isBlank()
                ? S3Client.builder().endpointOverride(URI.create("https://" + account + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto")).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(40)).apiCallAttemptTimeout(Duration.ofSeconds(20)))
                .build() : null;
    }
    @Override public boolean configured() { return client != null; }
    /** 原始字节上传，文件内容不进入 PostgreSQL。 */
    @Override public void put(String key, byte[] bytes) {
        requireClient().putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("audio/mpeg").build(), RequestBody.fromBytes(bytes));
    }
    /** 授权后按固定对象键读取。 */
    @Override public byte[] get(String key) {
        return requireClient().getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }
    /** 只有对象确实不存在才返回 false；权限或网络异常不能伪装成缺失再付费生成。 */
    @Override public boolean exists(String key) {
        try { requireClient().headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()); return true; }
        catch (S3Exception failure) { if (failure.statusCode() == 404) return false; throw failure; }
    }
    /** S3删除不存在的键也成功，适合持久任务重复重试。 */
    @Override public void delete(String key) { requireClient().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()); }
    /** 没有配置时明确失败。 */
    private S3Client requireClient() {
        if (client == null) throw new IllegalStateException("R2_NOT_CONFIGURED");
        return client;
    }
    /** 应用退出时清理 HTTP 客户端。 */
    @Override public void close() { if (client != null) client.close(); }
}
