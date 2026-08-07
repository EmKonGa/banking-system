package com.banking.common.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extends {@link ResponseEntityExceptionHandler} so that Spring's own MVC exceptions keep the
 * statuses the framework already assigns them. That base class is what makes the catch-all below
 * safe: {@code @ExceptionHandler} resolution picks the most specific match, so a malformed body
 * still maps to 400 and an unmatched path to 404 rather than being swept into a 500. Enumerating
 * those types by hand here instead would be a blacklist that silently misclassifies whatever it
 * forgets — the same shape of bug as a Resilience4j {@code record-exceptions} whitelist.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getStatus().value(), ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(401, "Invalid email or password"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage));
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(CallNotPermittedException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(503, "Service temporarily unavailable. Please retry shortly."));
    }

    /**
     * Authorization failures thrown by method security must go back to Spring Security, not be
     * answered here: {@code ExceptionTranslationFilter} is what decides between 401 (the caller is
     * anonymous and should authenticate) and 403 (the caller is known and simply may not). Handling
     * them in this advice would collapse that distinction and report every one as a 500.
     * <p>
     * Rethrowing the original exception is the supported way to decline: {@code
     * ExceptionHandlerExceptionResolver} recognises that the throwable it caught is the one it
     * passed in, resolves nothing, and lets it continue up the filter chain.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(Exception ex) throws Exception {
        throw ex;
    }

    /**
     * Everything else. Before this existed an unhandled exception forwarded to {@code /error},
     * which the security chain denied, so the caller saw an empty 403 — a database outage was
     * indistinguishable from an authorization failure. The {@code DispatcherType.ERROR} permit in
     * each service's SecurityConfig fixes that forward; this makes the common case not need it, and
     * gives the response the same {@link ErrorResponse} shape as every other error.
     * <p>
     * The message is deliberately generic. Whatever went wrong is in the log with a stack trace;
     * echoing it to the caller leaks schema names, hostnames and query fragments.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "An unexpected error occurred"));
    }

    public record ErrorResponse(int status, String message) {}
}
