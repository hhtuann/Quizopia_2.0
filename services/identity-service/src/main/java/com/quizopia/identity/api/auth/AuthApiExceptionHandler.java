package com.quizopia.identity.api.auth;

import com.quizopia.identity.application.account.DisplayNameInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthApiExceptionHandler {
    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String AUTH_VERIFICATION_FAILED = "AUTH_VERIFICATION_FAILED";
    static final String DISPLAY_NAME_INVALID = "DISPLAY_NAME_INVALID";

    @ExceptionHandler(AuthApiException.class)
    ResponseEntity<ApiErrorResponse> handleAuthFailure(AuthApiException failure, HttpServletRequest request) {
        return response(failure.code(), failure.getMessage(), failure.status(), request);
    }

    @ExceptionHandler(DisplayNameInvalidException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidDisplayName(
            DisplayNameInvalidException failure, HttpServletRequest request) {
        // Never echo the rejected value.
        return response(DISPLAY_NAME_INVALID, "Display name is invalid.", HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        ConstraintViolationException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception failure, HttpServletRequest request) {
        if (AuthController.CONFIRM_PATH.equals(request.getRequestURI())) {
            return response(AUTH_VERIFICATION_FAILED, "Email verification failed.", HttpStatus.BAD_REQUEST, request);
        }
        return response(INVALID_REQUEST, "Request validation failed.", HttpStatus.BAD_REQUEST, request);
    }

    private static ResponseEntity<ApiErrorResponse> response(
            String code, String message, HttpStatus status, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, status.value(), request.getRequestURI(), MDC.get("traceId")));
    }
}
