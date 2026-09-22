package com.languagelean.config;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 向前端提供可展示的业务错误，不泄露数据库语句和内部异常。 */
@RestControllerAdvice
class ApiExceptionHandler {
    /** 保留服务层给出的业务状态码和原因。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> business(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("detail",
                ex.getReason() == null ? "操作失败" : ex.getReason()));
    }

    /** 唯一键兜底处理并发创建；乐观锁失败要求用户重新读取。 */
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<Map<String, String>> conflict(Exception ex) {
        return ResponseEntity.status(409).body(Map.of("detail", "记录已存在或已被其他操作修改，请刷新后检查"));
    }
}
