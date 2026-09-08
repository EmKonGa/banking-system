package com.banking.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP against the two services, plus the tokens they expect.
 *
 * <p>Tokens are minted here rather than obtained from auth-service. The services validate them from
 * claims alone — {@code ClaimsJwtAuthFilter} does no database lookup — so running a third process to
 * issue them would add a dependency without adding coverage. That the shared signing secret makes
 * this possible is the design, not a shortcut around it.
 */
final class Api {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Comfortably past payment-service's own 15s Feign read timeout. Several tests deliberately hold
     * a response open and then assert on what payment-service did when <em>its</em> patience ran out;
     * a client that gave up first would turn that into a timeout of our own and prove nothing.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    record Response(int status, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException("not JSON: " + body, e);
            }
        }
    }

    /** A user, as the services see one: an id, an email and a role — nothing persisted anywhere. */
    record User(UUID id, String email, String role) {
        static User customer() {
            UUID id = UUID.randomUUID();
            return new User(id, "user-" + id + "@example.com", "USER");
        }

        static User admin() {
            UUID id = UUID.randomUUID();
            return new User(id, "admin-" + id + "@example.com", "ADMIN");
        }
    }

    record Account(UUID id, String number) {}

    private Api() {}

    static String tokenFor(User user) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())        // jti — what logout blacklists
                .subject(user.email())
                .claim("role", user.role())
                .claim("userId", user.id().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + Duration.ofMinutes(30).toMillis()))
                .signWith(Keys.hmacShaKeyFor(BankingStack.JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // --- the calls these tests make ---------------------------------------------------------

    static Account createAccount(User owner) {
        Response response = post(BankingStack.accountBaseUrl() + "/api/accounts", owner,
                Map.of("type", "CHECKING"));
        if (response.status() != 200 && response.status() != 201) {
            throw new IllegalStateException("could not create an account: " + response);
        }
        JsonNode body = response.json();
        return new Account(UUID.fromString(body.get("id").asText()), body.get("accountNumber").asText());
    }

    /** ADMIN-only, and a saga in its own right — the same {@code executeAndSettle} a transfer uses. */
    static Response deposit(User admin, String toAccountNumber, String amount, UUID idempotencyKey) {
        return post(BankingStack.paymentBaseUrl() + "/api/payments/deposit", admin, Map.of(
                "toAccountNumber", toAccountNumber,
                "amount", new BigDecimal(amount),
                "description", "test deposit",
                "idempotencyKey", idempotencyKey.toString()));
    }

    static Response transfer(User from, Account fromAccount, String toAccountNumber,
                             String amount, UUID idempotencyKey) {
        return post(BankingStack.paymentBaseUrl() + "/api/payments/transfer", from, Map.of(
                "fromAccountId", fromAccount.id().toString(),
                "toAccountNumber", toAccountNumber,
                "amount", new BigDecimal(amount),
                "description", "test transfer",
                "idempotencyKey", idempotencyKey.toString()));
    }

    // --- plumbing ---------------------------------------------------------------------------

    static Response post(String url, User as, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tokenFor(as))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
