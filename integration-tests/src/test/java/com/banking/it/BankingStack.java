package com.banking.it;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * account-service and payment-service running as separate processes, with a controllable proxy
 * between them.
 *
 * <p>Everything the transfer saga is built for happens across the boundary these containers create.
 * The existing unit tests mock {@code AccountServiceClient} and {@code TransferLedger}, so what they
 * verify is the poller's decision table against a stubbed answer — correct, and silent about whether
 * the two services agree in the first place. Three claims are only checkable here:
 *
 * <ul>
 *   <li>a crash between account-service's commit and payment-service's settlement leaves recoverable
 *       state — the window {@code openIntent} was introduced to survive;</li>
 *   <li>the 4xx-versus-indeterminate split holds against a Feign failure the runtime actually
 *       produced, rather than one a test constructed;</li>
 *   <li>{@code /internal/accounts/transfers/{key}} is authoritative because {@code AccountTransferLog}
 *       commits in the same transaction as the balance change — a claim about two schemas under two
 *       independent transaction managers.</li>
 * </ul>
 *
 * <h2>Shape of the stack</h2>
 *
 * <pre>
 *   payment-service ──► toxiproxy ──► account-service
 *          │                                  │
 *          └──────────► postgres ◄────────────┘   (banking_payment / banking_account)
 *                       redis
 * </pre>
 *
 * <p><strong>One Postgres, two schemas</strong> — the same arrangement compose and k8s run. What
 * makes the recovery lookup meaningful is not two servers but two owners with separate transactions,
 * which is exactly what the schemas give. A second container would test something the system does
 * not do.
 *
 * <p><strong>Toxiproxy is what makes the crash window an arrangeable state rather than a race.</strong>
 * "account-service committed and payment-service never found out" is otherwise reachable only by
 * killing a process at the right microsecond. Holding the response open makes it deterministic, and
 * — worth being clear about — produces the identical state: the money moved, and the intent is
 * PENDING with nothing to settle it.
 *
 * <p><strong>No Kafka.</strong> Nothing here asserts on notification delivery, and settlement writes
 * the outbox row in the same transaction as the ledger update, so an unpublishable row does not
 * affect a single assertion below. The producer is configured to give up quickly rather than sit in
 * {@code max.block.ms}. That the outbox poller keeps failing in the background is not incidental —
 * it is the R7 fix being exercised: before it, those blocking publish attempts ran on the request
 * thread and on the recovery poller's only scheduler thread.
 */
final class BankingStack {

    // Long enough for HS256 and not one of the placeholders SecretValidator refuses to boot on.
    static final String JWT_SECRET = "integration-test-signing-key-not-a-placeholder-256-bits-wide";
    static final String INTERNAL_SECRET = "integration-test-internal-secret";

    static final String DB_NAME = "banking_db";
    static final String DB_USER = "banking_user";
    static final String DB_PASSWORD = "banking_pass";
    static final String REDIS_PASSWORD = "redis_pass";

    private static final int ACCOUNT_PORT = 8082;
    private static final int PAYMENT_PORT = 8083;

    /**
     * The proxy's listen port inside the toxiproxy container. Fixed rather than allocated, because
     * payment-service is handed {@code ACCOUNT_SERVICE_URL} as an environment variable at container
     * start and cannot be told a port discovered later.
     */
    private static final int PROXY_PORT = 8666;

    /**
     * How long an intent may be in flight before the recovery poller treats it as stranded
     * (default 60s). Everything the poller does is gated on this, so the whole suite waits on it.
     */
    static final int GRACE_PERIOD_SECONDS = 5;

    /**
     * How old a not-found intent must be before it is written off (default 900s). Deliberately a
     * multiple of the grace period rather than equal to it: the asymmetry is the design — "it
     * happened" is acted on quickly, "it did not happen" has to persist — and collapsing the two
     * here would let a test pass against a system that had lost it.
     */
    static final int WRITE_OFF_SECONDS = 20;

    private static final Network NETWORK = Network.newNetwork();

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB_NAME)
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                    .withExposedPorts(6379)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis")
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("toxiproxy");

    static final GenericContainer<?> ACCOUNT_SERVICE = service("account-service", ACCOUNT_PORT)
            .withEnv("PAYMENT_SERVICE_URL", "http://payment-service:" + PAYMENT_PORT);

    static final GenericContainer<?> PAYMENT_SERVICE = service("payment-service", PAYMENT_PORT)
            // Through the proxy, never straight at the service. Every test that arranges a failure
            // does it here, and a call that bypassed the proxy would quietly be untestable.
            .withEnv("ACCOUNT_SERVICE_URL", "http://toxiproxy:" + PROXY_PORT)
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka-not-running:9092")
            .withCommand("java", "-jar", "/app/app.jar",
                    // Command-line args rather than environment variables for the test-only tuning:
                    // Spring's relaxed binding maps POLL_INTERVAL_MS to `poll.interval.ms`, not to
                    // `poll-interval-ms`, so the obvious env var would bind to nothing and leave the
                    // defaults quietly in place. Args take the property name literally.
                    "--transfer.recovery.poll-interval-ms=1000",
                    "--transfer.recovery.grace-period-seconds=" + GRACE_PERIOD_SECONDS,
                    "--transfer.recovery.write-off-seconds=" + WRITE_OFF_SECONDS,
                    // The scheduled outbox poll is not what these tests exercise; OutboxTrigger still
                    // fires on every commit, which is the path that used to run on the request thread.
                    "--outbox.poll-interval-ms=3600000",
                    "--kafka.publish.ack-timeout-ms=2000",
                    "--spring.kafka.producer.properties.max.block.ms=1000",
                    // Test pacing, not a behaviour change. Several tests deliberately fail enough
                    // calls to open the account-service breaker, and it is per-process — it outlives
                    // the test that opened it and would short-circuit the next one's first transfer.
                    // The breaker still opens and still short-circuits; only the cool-down is
                    // compressed, so SagaTestBase can wait for a healthy call path in a second
                    // rather than in thirty.
                    "--resilience4j.circuitbreaker.instances.account-service.wait-duration-in-open-state=1s",
                    "--resilience4j.circuitbreaker.instances.account-service.permitted-number-of-calls-in-half-open-state=1");

    private static Proxy accountProxy;
    private static boolean started;

    private BankingStack() {}

    /**
     * Starts the stack once for the whole suite and leaves it running; Ryuk removes the containers
     * when the JVM exits. Restarting a JVM-per-service stack between test classes would dominate the
     * runtime, and every test here already isolates itself with fresh accounts and fresh idempotency
     * keys.
     */
    static synchronized void start() {
        if (started) return;

        POSTGRES.start();
        REDIS.start();
        TOXIPROXY.start();
        createSchemas();
        createAccountProxy();

        // account-service first: payment-service's Feign client is not called at startup, but a
        // healthy peer makes an early failure mean what it says.
        ACCOUNT_SERVICE.start();
        PAYMENT_SERVICE.start();

        started = true;
    }

    /**
     * Flyway creates its own schema per service ({@code spring.flyway.schemas}), so this exists only
     * to make the JDBC URLs' {@code ?currentSchema=} resolvable at connection time — before the
     * first migration has run.
     */
    private static void createSchemas() {
        try (Connection c = adminConnection()) {
            c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS banking_account");
            c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS banking_payment");
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the service schemas", e);
        }
    }

    private static void createAccountProxy() {
        try {
            ToxiproxyClient client =
                    new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            accountProxy = client.createProxy(
                    "account-service", "0.0.0.0:" + PROXY_PORT, "account-service:" + ACCOUNT_PORT);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the account-service proxy", e);
        }
    }

    private static GenericContainer<?> service(String module, int port) {
        return new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre-jammy"))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(ServiceJar.of(module)), "/app/app.jar")
                .withCommand("java", "-jar", "/app/app.jar")
                .withExposedPorts(port)
                .withNetwork(NETWORK)
                .withNetworkAliases(module)
                .withEnv("DB_HOST", "postgres")
                .withEnv("DB_PORT", "5432")
                .withEnv("DB_USER", DB_USER)
                .withEnv("DB_PASSWORD", DB_PASSWORD)
                .withEnv("REDIS_HOST", "redis")
                .withEnv("REDIS_PASSWORD", REDIS_PASSWORD)
                .withEnv("JWT_SECRET", JWT_SECRET)
                .withEnv("INTERNAL_SECRET", INTERNAL_SECRET)
                .withEnv("MANAGEMENT_TRACING_ENABLED", "false")
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(module)))
                // The health endpoint, not a log line: the services are slow to boot under a cold
                // JVM and the budget has to cover Flyway plus context init on a loaded CI runner.
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forPort(port)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4)));
    }

    static Proxy accountProxy() {
        return accountProxy;
    }

    /**
     * SIGKILLs payment-service and starts the same container again — no shutdown hook, no graceful
     * anything, exactly what the saga's durable intent exists to survive.
     *
     * <p>Deliberately not {@code GenericContainer.stop()}/{@code start()}: that destroys the
     * container and builds a new one, which would also discard the state under test. This kills the
     * process and restarts what is left, so the service comes back to the database it left behind.
     */
    static void killAndRestartPaymentService() {
        DockerClient docker = DockerClientFactory.instance().client();
        String id = PAYMENT_SERVICE.getContainerId();

        docker.killContainerCmd(id).withSignal("SIGKILL").exec();
        docker.startContainerCmd(id).exec();

        // Docker re-allocates the published port on start, and Testcontainers cached the old one at
        // creation. Nothing warns about this: requests would simply go to a port nothing is on.
        paymentBaseUrl = null;
        awaitPaymentHealthy();
    }

    private static volatile String paymentBaseUrl;

    static synchronized String paymentBaseUrl() {
        if (paymentBaseUrl == null) {
            InspectContainerResponse info =
                    DockerClientFactory.instance().client()
                            .inspectContainerCmd(PAYMENT_SERVICE.getContainerId()).exec();
            Ports.Binding[] bindings = info.getNetworkSettings().getPorts().getBindings()
                    .get(ExposedPort.tcp(PAYMENT_PORT));
            if (bindings == null || bindings.length == 0) {
                throw new IllegalStateException("payment-service is not publishing " + PAYMENT_PORT);
            }
            paymentBaseUrl = "http://" + PAYMENT_SERVICE.getHost() + ":" + bindings[0].getHostPortSpec();
        }
        return paymentBaseUrl;
    }

    private static void awaitPaymentHealthy() {
        HttpClient client = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(4));
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest probe = HttpRequest.newBuilder(URI.create(paymentBaseUrl() + "/actuator/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                if (client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException | IllegalStateException ignored) {
                // Not up yet. A restarting JVM refuses connections before it answers 503.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            sleep(500);
        }
        throw new IllegalStateException("payment-service did not come back after being killed");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String accountBaseUrl() {
        return "http://" + ACCOUNT_SERVICE.getHost() + ":" + ACCOUNT_SERVICE.getMappedPort(ACCOUNT_PORT);
    }

    /**
     * Assertions read the two schemas directly rather than through the services' APIs. A transfer
     * that is PENDING in the ledger is not visible as such through any endpoint, and the whole point
     * of these tests is the state between the steps.
     */
    static Connection adminConnection() {
        try {
            return DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException("could not connect to the test database", e);
        }
    }
}
