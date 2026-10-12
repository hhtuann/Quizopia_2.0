package com.quizopia.assessment.api;

import com.quizopia.assessment.application.PublicationFailure;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class PublicationErrors {
    @ExceptionHandler(PublicationFailure.class)
    ResponseEntity<Map<String, String>> failure(PublicationFailure e) {
        return error(e.status(), e.code());
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.HttpMediaTypeNotSupportedException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return error(400, "INVALID_REQUEST");
    }

    private ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("code", code));
    }
}
