package com.banking.common.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @SuppressWarnings("unchecked")
    void validationErrorsAreKeyedByFieldName() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "transferRequest");
        bindingResult.addError(new FieldError("transferRequest", "amount", "Amount must be positive"));
        bindingResult.addError(new FieldError("transferRequest", "toAccountNumber", "must not be blank"));

        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("validationTarget", String.class), 0);
        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parameter, bindingResult),
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, String>) response.getBody())
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

    /**
     * The catch-all exists so an unhandled exception is a 500 with a body, rather than a forward to
     * /error that the security chain answers with an empty 403.
     */
    @Test
    void anUnhandledExceptionBecomesAFiveHundredWithABody() throws Exception {
        mockMvc().perform(post("/boom").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    /** Whatever failed is in the log; the caller must not be told the schema name. */
    @Test
    void theFiveHundredBodyDoesNotEchoTheUnderlyingFailure() throws Exception {
        mockMvc().perform(post("/boom").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("banking_auth.users"))));
    }

    /**
     * The reason this advice extends ResponseEntityExceptionHandler. HttpMessageNotReadableException
     * does <em>not</em> implement {@code ErrorResponse}, so it is exactly the sort of framework
     * exception a hand-maintained rethrow list would forget — and the catch-all would then report a
     * client's malformed JSON as a server fault, and page someone for it.
     */
    @Test
    void aMalformedRequestBodyIsStillAFourHundred() throws Exception {
        mockMvc().perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Method security must keep deciding 401-vs-403 for itself. If the catch-all swallowed this,
     * an ADMIN-only endpoint like /api/payments/deposit would answer an ordinary user with a 500.
     */
    @Test
    void anAuthorizationFailureIsNotConvertedIntoAFiveHundred() {
        assertThatThrownBy(() ->
                mockMvc().perform(post("/denied").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(handler)
                .build();
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/boom")
        public void boom() {
            throw new IllegalStateException("relation \"banking_auth.users\" does not exist");
        }

        @PostMapping("/denied")
        public void denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @PostMapping("/echo")
        public Map<String, String> echo(@RequestBody Map<String, String> body) {
            return body;
        }
    }

    @SuppressWarnings("unused")
    private void validationTarget(String body) {
        // Only used to obtain a MethodParameter for MethodArgumentNotValidException.
    }
}
