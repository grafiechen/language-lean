package com.languagelean.operations.backup;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import tools.jackson.databind.ObjectMapper;

/** 分块AES-256-GCM归档；认证块序号、长度和头部，终止块防截断，内存与归档大小无关。 */
public final class BackupArchive {
    private static final byte[] MAGIC = "LLBKP01\n".getBytes(StandardCharsets.US_ASCII);
    private static final int BLOCK = 65536;
    private static final ObjectMapper JSON = new ObjectMapper();
    private BackupArchive() {}
    /** 每个归档重新生成96位随机基础IV；每块通过序号派生不同IV。 */
    public static OutputStream encrypt(OutputStream target, String keyId, String keyBase64) throws IOException {
        if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("INVALID_BACKUP_KEY_ID");
        var nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        var key = key(keyBase64);
        var header = JSON.writeValueAsBytes(new Header(1, "AES-256-GCM", keyId, Base64.getEncoder().encodeToString(nonce), "POSTGRES_CUSTOM", BLOCK));
        var output = new DataOutputStream(target); output.write(MAGIC); output.writeInt(header.length); output.write(header);
        return new EncryptedOutput(output, key, nonce, aad(header));
    }
    /** 认证完整归档后才发布解密文件；失败删除暂存，不能恢复未经完整认证的正文。 */
    public static void decrypt(Path archive, Path destination, String expectedKeyId, String keyBase64) throws IOException {
        if (expectedKeyId == null || !expectedKeyId.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("INVALID_BACKUP_KEY_ID");
        var key = key(keyBase64); var absolute = destination.toAbsolutePath().normalize();
        if (Files.exists(absolute)) throw new IOException("RESTORE_OUTPUT_ALREADY_EXISTS");
        var staged = privateTemp(absolute.getParent(), "unsealed-"); boolean published = false;
        try {
            try (var input = new DataInputStream(Files.newInputStream(archive)); var output = Files.newOutputStream(staged)) {
                if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) throw new IOException("INVALID_BACKUP_FORMAT");
                int size = input.readInt(); if (size < 1 || size > 1024) throw new IOException("INVALID_BACKUP_HEADER");
                var headerBytes = input.readNBytes(size); if (headerBytes.length != size) throw new IOException("TRUNCATED_BACKUP");
                var header = JSON.readValue(headerBytes, Header.class);
                if (header == null || header.version() != 1 || !"AES-256-GCM".equals(header.algorithm()) || !"POSTGRES_CUSTOM".equals(header.format())
                        || !expectedKeyId.equals(header.keyId()) || header.blockSize() != BLOCK) throw new IOException("BACKUP_KEY_OR_FORMAT_MISMATCH");
                byte[] nonce;
                try { nonce = Base64.getDecoder().decode(header.nonce()); }
                catch (RuntimeException invalid) { throw new IOException("INVALID_BACKUP_NONCE"); }
                if (nonce.length != 12) throw new IOException("INVALID_BACKUP_NONCE");
                int index = 0; var associated = aad(headerBytes);
                while (true) {
                    int length = input.readInt(); if (length < 0 || length > BLOCK) throw new IOException("INVALID_BACKUP_BLOCK");
                    var encrypted = input.readNBytes(length + 16); if (encrypted.length != length + 16) throw new IOException("TRUNCATED_BACKUP");
                    var plain = crypt(Cipher.DECRYPT_MODE, key, nonce, associated, index, length, encrypted);
                    if (length == 0) { if (input.read() != -1) throw new IOException("TRAILING_BACKUP_DATA"); break; }
                    output.write(plain); Arrays.fill(plain, (byte) 0);
                    if (index == Integer.MAX_VALUE) throw new IOException("BACKUP_TOO_LARGE"); index++;
                }
            }
            Files.move(staged, absolute); published = true;
        } finally { if (!published) Files.deleteIfExists(staged); }
    }
    /** Linux生产创建0600文件；其他文件系统沿用受限运维目录权限。 */
    static Path privateTemp(Path directory, String prefix) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) return Files.createTempFile(directory, prefix, ".partial", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.createTempFile(directory, prefix, ".partial");
    }
    public static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256"); var buffer = new byte[BLOCK]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] aad(byte[] header) throws IOException { var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.write(MAGIC); out.writeInt(header.length); out.write(header); return bytes.toByteArray(); }
    private static SecretKeySpec key(String base64) throws IOException {
        byte[] bytes = null;
        try { bytes = Base64.getDecoder().decode(base64); if (bytes.length != 32) throw new IllegalArgumentException(); return new SecretKeySpec(bytes, "AES"); }
        catch (RuntimeException invalid) { throw new IOException("INVALID_BACKUP_KEY"); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
    private static byte[] crypt(int mode, SecretKeySpec key, byte[] baseNonce, byte[] aad, int index, int length, byte[] bytes) throws IOException {
        try {
            var nonce = baseNonce.clone(); var counter = ByteBuffer.allocate(4).putInt(index).array();
            for (int i = 0; i < 4; i++) nonce[8 + i] ^= counter[i];
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(mode, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(aad);
            cipher.updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(length).array()); return cipher.doFinal(bytes);
        } catch (GeneralSecurityException invalid) { throw new IOException("BACKUP_AUTHENTICATION_FAILED"); }
    }
    private record Header(int version, String algorithm, String keyId, String nonce, String format, int blockSize) {}
    private static final class EncryptedOutput extends OutputStream {
        private final DataOutputStream target; private final SecretKeySpec key; private final byte[] nonce, aad;
        private final byte[] buffer = new byte[BLOCK]; private int used, index; private boolean closed;
        EncryptedOutput(DataOutputStream target, SecretKeySpec key, byte[] nonce, byte[] aad) { this.target = target; this.key = key; this.nonce = nonce; this.aad = aad; }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length); if (closed) throw new IOException("BACKUP_STREAM_CLOSED");
            while (length > 0) { int count = Math.min(length, BLOCK - used); System.arraycopy(bytes, offset, buffer, used, count); used += count; offset += count; length -= count; if (used == BLOCK) block(); }
        }
        private void block() throws IOException {
            target.writeInt(used); target.write(crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, index, used, Arrays.copyOf(buffer, used)));
            Arrays.fill(buffer, (byte) 0); used = 0; if (index == Integer.MAX_VALUE) throw new IOException("BACKUP_TOO_LARGE"); index++;
        }
        @Override public void flush() throws IOException { if (closed) throw new IOException("BACKUP_STREAM_CLOSED"); target.flush(); }
        @Override public void close() throws IOException {
            if (closed) return;
            try { if (used > 0) block(); block(); }
            finally { closed = true; Arrays.fill(buffer, (byte) 0); target.close(); }
        }
    }
}
