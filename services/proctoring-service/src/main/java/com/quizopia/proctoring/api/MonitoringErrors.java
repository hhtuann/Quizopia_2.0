package com.quizopia.proctoring.api;

import com.quizopia.proctoring.application.MonitoringFailure;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class MonitoringErrors {
    @ExceptionHandler(MonitoringFailure.class)
    ResponseEntity<Map<String, String>> failure(MonitoringFailure f) {
        return error(f.status(), f.code());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.HttpMediaTypeNotSupportedException.class
    })
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return error(400, "INVALID_REQUEST");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Map<String, String>> persistence(Exception e) {
        return error(503, "PERSISTENCE_UNAVAILABLE");
    }

    private ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("code", code));
    }
}
