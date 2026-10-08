package com.quizopia.quiz.api;

import com.quizopia.quiz.application.InvalidQuizLibraryRequestException;
import com.quizopia.quiz.application.InvalidQuizVersionRequestException;
import com.quizopia.quiz.application.QuizDraftNotFoundException;
import com.quizopia.quiz.application.QuizMarkdownInvalidException;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
import com.quizopia.quiz.application.QuizVersionNotFoundException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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
    private final Tracer tracer;

    public QuizApiExceptionHandler(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        InvalidQuizLibraryRequestException.class,
        InvalidQuizVersionRequestException.class
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

    @ExceptionHandler(QuizVersionNotFoundException.class)
    ResponseEntity<QuizApiError> quizVersionNotFound(
            QuizVersionNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "QUIZ_VERSION_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({QuizOwnershipDeniedException.class, AccessDeniedException.class})
    ResponseEntity<QuizApiError> accessDenied(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied", request);
    }

    @ExceptionHandler(QuizMarkdownInvalidException.class)
    ResponseEntity<QuizMarkdownValidationErrorResponse> quizMarkdownInvalid(
            QuizMarkdownInvalidException exception, HttpServletRequest request) {
        var errors =
                exception.errors().stream().map(QuizMarkdownErrorResponse::from).toList();
        return ResponseEntity.badRequest()
                .body(new QuizMarkdownValidationErrorResponse(
                        "QUIZ_MARKDOWN_INVALID",
                        "Quiz Markdown validation failed.",
                        HttpStatus.BAD_REQUEST.value(),
                        request.getRequestURI(),
                        currentTraceId(),
                        errors));
    }

    private static ResponseEntity<QuizApiError> response(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new QuizApiError(
                        code, Objects.requireNonNull(message, "message"), status.value(), request.getRequestURI()));
    }

    private String currentTraceId() {
        if (tracer == null) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        Span current = tracer.currentSpan();
        if (current != null) {
            return current.context().traceId();
        }

        Span fallback = tracer.nextSpan().name("quiz-markdown-validation-error").start();
        try {
            return fallback.context().traceId();
        } finally {
            fallback.end();
        }
    }
}
