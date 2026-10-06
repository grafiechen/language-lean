package com.languagelean.operations.backup;
import java.nio.file.Path;
/** 单独的私有灾备桶边界；不得复用音频播放API或返回公开下载地址。 */
interface BackupObjectStore {
    boolean configured();
    String scopeId();
    void uploadAndVerify(String key, Path encrypted, String sha256) throws Exception;
    void delete(String key);
}
