package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizDraftNotFoundException;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
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
public final class QuizApiExceptionHandler {
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<QuizApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request is invalid", request);
    }

    @ExceptionHandler(QuizNotFoundException.class)
    ResponseEntity<QuizApiError> quizNotFound(QuizNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(QuizDraftNotFoundException.class)
    ResponseEntity<QuizApiError> quizDraftNotFound(QuizDraftNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "QUIZ_DRAFT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({QuizOwnershipDeniedException.class, AccessDeniedException.class})
    ResponseEntity<QuizApiError> accessDenied(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied", request);
    }

    private static ResponseEntity<QuizApiError> response(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new QuizApiError(
                        code, Objects.requireNonNull(message, "message"), status.value(), request.getRequestURI()));
    }
}
