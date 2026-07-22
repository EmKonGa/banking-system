package com.banking.common.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This handler decides what every service tells a client when something goes wrong, so its
 * mappings are effectively part of the public API. Two of them also carry security weight: the
 * login failure must stay generic, and an open circuit must be distinguishable from a real error.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** AppException carries its own status — that is the whole reason it exists. */
    @Test
    void appExceptionKeepsItsOwnStatusAndMessage() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAppException(new AppException("Account not found", HttpStatus.NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("Account not found");
    }

    @Test
    void appExceptionWithABadRequestStatusIsPassedThroughToo() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAppException(new AppException("Amount must be positive", HttpStatus.BAD_REQUEST));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
    }

    /**
     * The reply must not reveal whether the email exists — "no such user" and "wrong password"
     * have to be indistinguishable, or the login endpoint becomes a user-enumeration oracle. The
     * handler therefore discards the exception's own message.
     */
    @Test
    void badCredentialsAlwaysYieldsTheSameGenericMessage() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> forUnknownUser =
                handler.handleBadCredentials(new BadCredentialsException("User not found: a@b.com"));
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> forWrongPassword =
                handler.handleBadCredentials(new BadCredentialsException("Bad password"));

        assertThat(forUnknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forUnknownUser.getBody()).isNotNull();
        assertThat(forWrongPassword.getBody()).isNotNull();
        assertThat(forUnknownUser.getBody().message()).isEqualTo("Invalid email or password");
        assertThat(forUnknownUser.getBody().message()).isEqualTo(forWrongPassword.getBody().message());
        assertThat(forUnknownUser.getBody().message()).doesNotContain("a@b.com");
    }

    /** Validation failures come back keyed by field so the frontend can attach them to inputs. */
    @Test
    void validationErrorsAreKeyedByFieldName() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "transferRequest");
        bindingResult.addError(new FieldError("transferRequest", "amount", "Amount must be positive"));
        bindingResult.addError(new FieldError("transferRequest", "toAccountNumber", "must not be blank"));

        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("validationTarget", String.class), 0);
        ResponseEntity<Map<String, String>> response =
                handler.handleValidation(new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("amount", "Amount must be positive")
                .containsEntry("toAccountNumber", "must not be blank");
    }

    /**
     * An open breaker means "we did not try", not "we tried and failed". 503 tells the client the
     * request is worth retrying shortly, which a 500 would not.
     */
    @Test
    void openCircuitBecomesRetryableServiceUnavailable() {
        CircuitBreaker breaker = CircuitBreaker.of("redis", CircuitBreakerConfig.ofDefaults());
        breaker.transitionToOpenState();

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleCircuitOpen(CallNotPermittedException.createCallNotPermittedException(breaker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(503);
        assertThat(response.getBody().message()).contains("retry");
    }

    @SuppressWarnings("unused")
    private void validationTarget(String body) {
        // Only used to obtain a MethodParameter for MethodArgumentNotValidException.
    }
}
