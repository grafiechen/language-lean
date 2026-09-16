package com.languagelean.audio;

import java.util.UUID;

/**
 * 音频生成应用端口。
 *
 * <p>后续基础设施适配器负责加锁、锁内复查、TTS 调用和 R2 上传，
 * 业务服务不直接依赖云厂商 SDK。</p>
 */
public interface AudioGenerationPort {
    /** 调用方据此区分可播放、生成中、缺少发音、关闭和失败状态。 */
    enum Status { READY, PENDING, MISSING_PRONUNCIATION, DISABLED, FAILED }
    /** 确保生成后的状态；没有就绪版本时 audioVersionId 可以为空。 */
    record Result(Status status, UUID audioVersionId) {}
    /** 为当前用户有权限访问的音频资产确保存在可用版本。 */
    Result ensureGenerated(UUID authenticatedUserId, UUID audioAssetId);
}
