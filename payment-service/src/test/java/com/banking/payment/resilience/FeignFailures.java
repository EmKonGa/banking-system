package com.banking.payment.resilience;

import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Transport failures produced by actually causing them, rather than constructed by hand.
 *
 * <p>Shared by the predicate's own test and the wiring test, and worth the small amount of plumbing
 * for one reason: the bug being fixed was a config entry naming an exception the runtime never
 * throws. Any fixture that fabricates the hoped-for exception agrees with the broken config. These
 * two are the real thing, from a real client against a real socket.
 */
final class FeignFailures {

    private FeignFailures() {
    }

    interface StubApi {
        @RequestLine("GET /probe")
        String probe();
    }

    /** Connection refused: a port claimed to get a free number, then released. */
    static Throwable connectionRefused() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        return failureFrom("http://127.0.0.1:" + port, 2000, 2000);
    }

    /** Read timeout: a server that accepts the connection and never answers. */
    static Throwable readTimeout() throws Exception {
        CountDownLatch stop = new CountDownLatch(1);
        try (ServerSocket stalling = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (var ignored = stalling.accept()) {
                    stop.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // socket closed as the caller finishes
                }
            });
            server.setDaemon(true);
            server.start();

            return failureFrom("http://127.0.0.1:" + stalling.getLocalPort(), 2000, 300);
        } finally {
            stop.countDown();
        }
    }

    /** NEVER_RETRY mirrors Spring Cloud's default and keeps Feign's own retryer out of the way. */
    private static Throwable failureFrom(String url, int connectMs, int readMs) {
        StubApi api = Feign.builder()
                .options(new Request.Options(connectMs, TimeUnit.MILLISECONDS, readMs, TimeUnit.MILLISECONDS, true))
                .retryer(Retryer.NEVER_RETRY)
                .target(StubApi.class, url);
        try {
            api.probe();
            throw new AssertionError("expected the call to fail");
        } catch (Throwable t) {
            return t;
        }
    }
}
