package com.banking.account.config;

import com.banking.common.security.JwtService;
import com.banking.common.security.TokenBlacklistService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Security applies its filter chain to the ERROR dispatch as well as the original request.
 * With {@code .anyRequest().authenticated()} and no permit for that dispatch, an unhandled failure
 * forwarded to {@code /error} was denied, and the caller received an empty 403 — so a database
 * outage was indistinguishable from an authorization failure. Demonstrated end to end 2026-08-04
 * against a dropped schema.
 * <p>
 * The catch-all in {@code GlobalExceptionHandler} answers most failures before they ever reach that
 * forward, which is precisely why this needs its own test: the remaining cases — a failure thrown
 * in a filter, or a container-issued {@code sendError} — are the ones no other test exercises.
 */
@WebMvcTest(controllers = ErrorDispatchSecurityTest.ProbeController.class)
@Import(SecurityConfig.class)
class ErrorDispatchSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    /**
     * The point of the fix, phrased as the caller sees it: the forward that a failed request makes
     * to /error reaches the error controller and comes back as a real 500 with a body, rather than
     * being denied by the chain and coming back as an empty 403.
     */
    @Test
    void aFailedRequestComesBackAsAFiveHundredAndNotAnEmptyForbidden() throws Exception {
        mockMvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/accounts");
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                            new IllegalStateException("relation \"banking_account.accounts\" does not exist"));
                    return request;
                }))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    /**
     * The permit is scoped to the dispatch type, not to the path — permitting {@code /error}
     * as a path would also expose it to a direct external GET.
     */
    @Test
    void aDirectRequestToErrorIsStillRejected() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isForbidden());
    }

    /** Ordinary protected endpoints are unaffected. */
    @Test
    void anOrdinaryRequestStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        public String probe() {
            return "ok";
        }
    }
}
