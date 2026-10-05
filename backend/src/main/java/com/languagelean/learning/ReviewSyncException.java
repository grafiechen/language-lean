package com.languagelean.learning;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 稳定同步错误码与中文说明分开，客户端不解析文案来判断删除或重置。 */
class ReviewSyncException extends ResponseStatusException {
    private final String code;
    /** HTTP 状态用于重试边界，code 用于明确的冲突处理。 */
    ReviewSyncException(HttpStatus status, String code, String detail) {
        super(status, detail); this.code = code;
    }
    String code() { return code; }
}
