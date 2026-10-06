package com.languagelean.operations.backup;

import java.nio.file.Path;
import java.nio.file.Files;

/** 运维创建/解密入口，不启动Web服务，不提供自动恢复生产数据库。 */
public final class BackupArchiveCommand {
    private BackupArchiveCommand() {}
    public static void main(String[] args) {
        if (args.length != 2) { System.err.println("Usage: BackupArchiveCommand <encrypted-archive> <new-pg-dump-output> OR --create <new-encrypted-archive>"); System.exit(2); }
        try {
            if (args[0].equals("--create")) {
                var target = Path.of(args[1]).toAbsolutePath().normalize();
                if (Files.exists(target)) throw new java.io.IOException("BACKUP_OUTPUT_ALREADY_EXISTS");
                var config = new DatabaseBackupConfiguration();
                config.setEncryptionKey(java.util.Objects.requireNonNullElse(System.getenv("BACKUP_ENCRYPTION_KEY"), ""));
                config.setKeyId(java.util.Objects.requireNonNullElse(System.getenv("BACKUP_KEY_ID"), ""));
                var dump = new PostgresDatabaseDump(config, java.util.Objects.requireNonNullElse(System.getenv("DB_URL"), ""),
                    System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
                Path temporary = null;
                try { temporary = dump.encryptedDump(); Files.move(temporary, target); }
                finally { if (temporary != null) Files.deleteIfExists(temporary); }
                System.out.println("DATABASE_BACKUP_CREATED"); return;
            }
            BackupArchive.decrypt(Path.of(args[0]), Path.of(args[1]), System.getenv("BACKUP_KEY_ID"), System.getenv("BACKUP_ENCRYPTION_KEY"));
            System.out.println("BACKUP_AUTHENTICATED_AND_DECRYPTED");
        } catch (Exception failed) { System.err.println("BACKUP_OPERATION_FAILED"); System.exit(1); }
    }
}
