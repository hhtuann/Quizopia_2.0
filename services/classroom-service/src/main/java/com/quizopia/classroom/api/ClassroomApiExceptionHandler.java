package com.quizopia.classroom.api;

import com.quizopia.classroom.application.ClassroomNotFoundException;
import com.quizopia.classroom.application.ClassroomOwnershipDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class ClassroomApiExceptionHandler {
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ClassroomApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request is invalid", request);
    }

    @ExceptionHandler(ClassroomNotFoundException.class)
    ResponseEntity<ClassroomApiError> classroomNotFound(
            ClassroomNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "CLASSROOM_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({ClassroomOwnershipDeniedException.class, AccessDeniedException.class})
    ResponseEntity<ClassroomApiError> accessDenied(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied", request);
    }

    private static ResponseEntity<ClassroomApiError> response(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ClassroomApiError(
                        code, Objects.requireNonNull(message, "message"), status.value(), request.getRequestURI()));
    }
}
