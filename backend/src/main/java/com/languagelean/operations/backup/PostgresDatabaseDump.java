package com.languagelean.operations.backup;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 压缩pg_dump输出直接进入GCM流，禁止明文转储和拼接shell命令。 */
@Component
class PostgresDatabaseDump implements DatabaseDumpPort {
    private final DatabaseBackupConfiguration config;
    private final String username, password;
    private final URI database;
    private static final Path EXECUTABLE = Path.of("/usr/lib/postgresql/17/bin/pg_dump");
    PostgresDatabaseDump(DatabaseBackupConfiguration config, @Value("${spring.datasource.url}") String url,
                         @Value("${spring.datasource.username}") String username, @Value("${spring.datasource.password}") String password) {
        this.config = config; this.username = username; this.password = password;
        URI parsed; try { parsed = URI.create(url.startsWith("jdbc:") ? url.substring(5) : url); } catch (IllegalArgumentException invalid) { parsed = null; }
        database = parsed;
    }
    public boolean configured() { return username != null && !username.isBlank() && password != null && Files.isExecutable(EXECUTABLE) && database != null && "postgresql".equals(database.getScheme()) && database.getHost() != null
        && database.getPath() != null && database.getPath().matches("/[A-Za-z0-9_-]+") && database.getUserInfo() == null && database.getQuery() == null && database.getFragment() == null; }
    public Path encryptedDump() throws Exception {
        if (!configured() || !config.encryptionConfigured()) throw new IOException("BACKUP_DUMP_NOT_CONFIGURED");
        var directory = Path.of(config.getDirectory()).toAbsolutePath().normalize(); Files.createDirectories(directory);
        var output = BackupArchive.privateTemp(directory, "encrypted-backup-");
        var builder = new ProcessBuilder(EXECUTABLE.toString(), "--format=custom", "--compress=gzip:6", "--no-owner", "--no-acl", "--no-password",
            "--exclude-table-data=public.password_transport_key", "--exclude-table-data=public.account_password_reset");
        var environment = builder.environment(); environment.clear();
        environment.put("PGHOST", database.getHost()); environment.put("PGPORT", Integer.toString(database.getPort() < 0 ? 5432 : database.getPort()));
        environment.put("PGDATABASE", database.getPath().substring(1)); environment.put("PGUSER", username); environment.put("PGPASSWORD", password);
        // 错误可能包含数据库连接信息，禁止复制到日志；通过退出码判断失败。
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = null; boolean completed = false; var executor = Executors.newSingleThreadExecutor();
        try {
            process = builder.start(); var running = process;
            var transfer = executor.submit(() -> {
                try (var input = running.getInputStream(); var file = Files.newOutputStream(output); var encrypted = BackupArchive.encrypt(file, config.getKeyId(), config.getEncryptionKey())) {
                    var signature = input.readNBytes(5);
                    if (!java.util.Arrays.equals(signature, new byte[]{'P','G','D','M','P'})) throw new IOException("INVALID_POSTGRES_ARCHIVE");
                    encrypted.write(signature); input.transferTo(encrypted); return true;
                }
            });
            transfer.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("POSTGRES_DUMP_FAILED");
            completed = true; return output;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS);
            if (!completed) Files.deleteIfExists(output);
        }
    }
}
