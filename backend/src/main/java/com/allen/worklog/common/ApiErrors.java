package com.allen.worklog.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) { return error(e.status(), e.code(), e.getMessage()); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> oversized(Exception e) { return error(413,"FILE_TOO_LARGE","导入文件不能超过 2 MB"); }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e) { return error(400, "INVALID_REQUEST", "请求格式不正确，请检查日期和输入字段"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) { return error(409, "DATA_CONFLICT", "数据重复或关联已变化，请刷新后检查输入"); }
    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<?> busy(Exception e) { return error(409, "CONCURRENT_UPDATE", "数据正在处理，请刷新后重试"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable(Exception e) {
        log.error("Database operation failed: {}", e.getClass().getSimpleName());
        return error(503, "DATABASE_UNAVAILABLE", "数据服务暂不可用，当前输入尚未保存，请稍后重试");
    }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
