package com.languagelean.audio;

/** 音频对象存储端口；业务层不接触 S3 SDK 或凭据。 */
public interface AudioObjectStore {
    /** 是否已配置可用的对象存储。 */
    boolean configured();
    /** 完成上传后才能切换当前版本。 */
    void put(String key, byte[] mp3);
    /** 读取已授权版本的文件。 */
    byte[] get(String key);
    /** 检查已记录文件是否仍存在。 */
    boolean exists(String key);
    /** 幂等删除已授权清除的私人音频，文件不存在视为成功。 */
    void delete(String key);
}
