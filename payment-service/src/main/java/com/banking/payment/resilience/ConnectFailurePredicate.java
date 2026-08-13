package com.banking.payment.resilience;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.function.Predicate;

/**
 * True only for failures that prove the request never reached account-service.
 *
 * <p>This exists because a class whitelist cannot express the question. The retry config used to
 * name {@code java.net.ConnectException} directly, which never matched anything:
 * {@code FeignException.errorExecuting} wraps <em>every</em> {@code IOException} in a
 * {@code feign.RetryableException}, and Resilience4j matches the thrown class with
 * {@code exClass.isAssignableFrom(e.getClass())} — no cause unwrapping. So the entry named an
 * exception the runtime never throws, and {@code @Retry} on {@code executeTransfer} retried nothing
 * at all. A rolling restart of account-service turned transfers into stranded PENDING intents
 * instead of retrying once against a healthy pod.
 *
 * <p><strong>Adding {@code feign.RetryableException} to the whitelist would have been the wrong
 * fix.</strong> That class also wraps {@code SocketTimeoutException}, and a read timeout means the
 * request was sent and may have been processed — the money may already have moved. The saga has a
 * considered answer for that case and it is not "send it again": leave the intent PENDING and let
 * {@code TransferRecoveryPoller} establish what actually happened. Retrying is reserved for the case
 * where there is nothing to establish.
 *
 * <p>So the split is by <em>what the failure proves</em> rather than by which class carries it:
 *
 * <ul>
 *   <li><strong>Retried</strong> — the connection was never established, so no bytes reached
 *       account-service and no money moved. Refused, unroutable, or unresolvable.</li>
 *   <li><strong>Not retried</strong> — anything else, including a read timeout and a connection
 *       reset mid-request. Outcome unknown, which is exactly what PENDING means.</li>
 * </ul>
 *
 * <p>Note {@code SocketTimeoutException} is deliberately absent even though a <em>connect</em>
 * timeout also throws it: the only thing separating it from a read timeout is the message text, and
 * a safety property should not rest on string matching. A connect timeout is therefore treated as
 * indeterminate — the conservative direction, and cheap now that the client has an explicit 2s
 * connect timeout rather than Feign's default 10s.
 *
 * <p>Retrying at all is only safe because the idempotency key is generated before the first call and
 * reused across attempts. That is the difference between this retry layer and any client-visible
 * one, which regenerates the key and moves the money twice.
 */
public class ConnectFailurePredicate implements Predicate<Throwable> {

    /**
     * Bounds the walk. Nothing legitimate nests this deep, and a cause chain <em>can</em> contain a
     * cycle: {@code Throwable.initCause} rejects a direct self-reference but happily permits
     * {@code a → b → a}. This runs on every failed call, so an unbounded walk would hang the request
     * thread rather than fail it.
     */
    private static final int MAX_DEPTH = 10;

    @Override
    public boolean test(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
            if (provesNothingWasSent(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean provesNothingWasSent(Throwable cause) {
        return cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof NoRouteToHostException;
    }
}
