package io.github.santoshmyakala.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.santoshmyakala.orders.config.SecurityConfig;
import io.github.santoshmyakala.orders.outbox.OutboxRelay;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole service against a real PostgreSQL (Testcontainers) and a real HTTP stub of the ERP. */
@SpringBootTest(properties = {
        "management.server.port=", // actuator on the main port so MockMvc can reach it
        "logging.structured.format.console=", // readable test output
        "orders.erp.read-timeout=500ms",
        "orders.outbox.initial-delay=1h", // tests drive the relay themselves
        "resilience4j.retry.instances.erp.wait-duration=10ms",
})
@AutoConfigureMockMvc
@Import(OrdersApiTest.Containers.class)
class OrdersApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @DynamicPropertySource
    static void erp(DynamicPropertyRegistry registry) throws IOException {
        String url = StubErp.start();
        registry.add("orders.erp.base-url", () -> url);
    }

    static final RequestPostProcessor PLANNER = user("planner");
    static final RequestPostProcessor OPERATOR = user("operator");
    static final RequestPostProcessor VIEWER = user("viewer");

    /** A JWT with the given role in its roles claim, mapped by the application's own converter. */
    static RequestPostProcessor user(String role) {
        return jwt().jwt(j -> j.claim("roles", List.of(role)).subject(role + "-1")).authorities(SecurityConfig.rolesClaim());
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired OutboxRelay relay;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        StubErp.reset();
        breakers.circuitBreaker("erp").reset();
        relay.relayBatch(); // start every test with a drained outbox
    }

    static String order(String product, int quantity, String line) {
        return """
                {"productCode":"%s","quantity":%d,"line":"%s","dueDate":"%s"}"""
                .formatted(product, quantity, line, LocalDate.now().plusDays(7));
    }

    ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor who) throws Exception {
        return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    UUID createOrder() throws Exception {
        return UUID.fromString(body(perform(post("/api/orders").content(order("GEARBOX-12", 40, "LINE-1")), PLANNER)
                .andExpect(status().isCreated())).get("id").asString());
    }

    @Test
    void plansAnOrderAndReadsItBack() throws Exception {
        var created = perform(post("/api/orders").content(order("GEARBOX-12", 40, "LINE-1")), PLANNER)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.matchesPattern("PO-\\d{4}-\\d{6}")));
        String location = created.andReturn().getResponse().getHeader("Location");

        perform(get(location), VIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("GEARBOX-12"))
                .andExpect(jsonPath("$.quantity").value(40));
    }

    @Test
    void rejectsInvalidRequestsListingEveryField() throws Exception {
        perform(post("/api/orders").content("""
                {"productCode":"gearbox 12","quantity":0,"line":"","dueDate":"2020-01-01"}"""), PLANNER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://errors.orders.example/validation-failed"))
                .andExpect(jsonPath("$.errors.productCode").exists())
                .andExpect(jsonPath("$.errors.quantity").exists())
                .andExpect(jsonPath("$.errors.line").exists())
                .andExpect(jsonPath("$.errors.dueDate").exists());
    }

    @Test
    void enforcesRoles() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        perform(post("/api/orders").content(order("X-1", 1, "LINE-1")), VIEWER).andExpect(status().isForbidden());
        UUID id = createOrder();
        perform(post("/api/orders/" + id + "/release"), OPERATOR).andExpect(status().isForbidden());
        perform(post("/api/orders/" + id + "/start"), PLANNER).andExpect(status().isForbidden());
        perform(post("/api/orders/" + id + "/cancel"), VIEWER).andExpect(status().isForbidden());
    }

    @Test
    void idempotencyKeyMakesRetriesSafe() throws Exception {
        String key = "plan-" + UUID.randomUUID();
        String first = body(perform(post("/api/orders").header("Idempotency-Key", key).content(order("PUMP-3", 10, "LINE-2")), PLANNER)
                .andExpect(status().isCreated())).get("id").asString();

        perform(post("/api/orders").header("Idempotency-Key", key).content(order("PUMP-3", 10, "LINE-2")), PLANNER)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));

        perform(post("/api/orders").header("Idempotency-Key", key).content(order("PUMP-3", 99, "LINE-2")), PLANNER)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://errors.orders.example/idempotency-key-reused"));
    }

    @Test
    void concurrentRetriesWithOneKeyCreateOneOrder() throws Exception {
        String key = "storm-" + UUID.randomUUID();
        String request = order("VALVE-7", 5, "LINE-3");
        int clients = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit((Callable<String>) () -> {
                    go.await();
                    var r = perform(post("/api/orders").header("Idempotency-Key", key).content(request), PLANNER).andReturn();
                    assertThat(r.getResponse().getStatus()).isIn(200, 201);
                    return json.readTree(r.getResponse().getContentAsString()).get("id").asString();
                }));
            }
            go.countDown();
            List<String> ids = new ArrayList<>();
            for (var f : results) {
                ids.add(f.get());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(jdbc.sql("select count(*) from production_orders where product_code = 'VALVE-7'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void releaseReservesMaterialIdempotentlyAndRecordsEvents() throws Exception {
        UUID id = createOrder();
        perform(post("/api/orders/" + id + "/release").header("If-Match", "\"0\""), PLANNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.reservationId").value("RES-1"))
                .andExpect(header().string("ETag", "\"1\""));
        String number = body(perform(get("/api/orders/" + id), VIEWER)).get("orderNumber").asString();
        assertThat(StubErp.CALLS).containsExactly("reserve-" + number);

        List<String> events = jdbc.sql("select event_type from outbox_events where aggregate_id = :id order by id")
                .param("id", id).query(String.class).list();
        assertThat(events).containsExactly("ProductionOrderPlanned", "ProductionOrderReleased");
    }

    @Test
    void insufficientMaterialIsABusinessAnswerNotRetried() throws Exception {
        UUID id = createOrder();
        StubErp.MODE.set(StubErp.Mode.INSUFFICIENT);
        perform(post("/api/orders/" + id + "/release"), PLANNER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://errors.orders.example/insufficient-material"));
        assertThat(StubErp.CALLS).hasSize(1);
        perform(get("/api/orders/" + id), VIEWER).andExpect(jsonPath("$.status").value("PLANNED"));
        assertThat(breakers.circuitBreaker("erp").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void erpOutageIsRetriedThenReportedAsRetryLater() throws Exception {
        UUID id = createOrder();
        StubErp.MODE.set(StubErp.Mode.DOWN);
        perform(post("/api/orders/" + id + "/release"), PLANNER)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.type").value("https://errors.orders.example/material-service-unavailable"));
        assertThat(StubErp.CALLS).hasSize(3); // max-attempts
        perform(get("/api/orders/" + id), VIEWER).andExpect(jsonPath("$.status").value("PLANNED"));
    }

    @Test
    void slowErpHitsTheReadTimeout() throws Exception {
        UUID id = createOrder();
        StubErp.MODE.set(StubErp.Mode.SLOW);
        long start = System.nanoTime();
        perform(post("/api/orders/" + id + "/release"), PLANNER).andExpect(status().isServiceUnavailable());
        long millis = (System.nanoTime() - start) / 1_000_000;
        // 3 attempts × 500 ms read timeout + backoff, far below 3 × 1.5 s of waiting for the stub
        assertThat(millis).isLessThan(2_500);
    }

    @Test
    void circuitOpensAndStopsCallingAFailingErp() throws Exception {
        StubErp.MODE.set(StubErp.Mode.DOWN);
        for (int i = 0; i < 2; i++) { // 2 requests × 3 attempts = 6 failed calls ≥ minimum of 5
            perform(post("/api/orders/" + createOrder() + "/release"), PLANNER).andExpect(status().isServiceUnavailable());
        }
        assertThat(breakers.circuitBreaker("erp").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsSoFar = StubErp.CALLS.size();

        perform(post("/api/orders/" + createOrder() + "/release"), PLANNER).andExpect(status().isServiceUnavailable());
        assertThat(StubErp.CALLS).hasSize(callsSoFar); // rejected locally, the ERP is left alone
    }

    @Test
    void shopFloorLifecycleWithOptimisticLocking() throws Exception {
        UUID id = createOrder();
        perform(post("/api/orders/" + id + "/release"), PLANNER).andExpect(status().isOk());
        perform(post("/api/orders/" + id + "/start").header("If-Match", "\"0\""), OPERATOR)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://errors.orders.example/stale-version"));
        perform(post("/api/orders/" + id + "/start").header("If-Match", "\"1\""), OPERATOR).andExpect(status().isOk());
        perform(post("/api/orders/" + id + "/complete").content("""
                {"goodQuantity":38,"scrapQuantity":2}"""), OPERATOR)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.goodQuantity").value(38));

        perform(post("/api/orders/" + id + "/cancel"), PLANNER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://errors.orders.example/invalid-transition"));
        perform(post("/api/orders/" + id + "/release"), PLANNER).andExpect(status().isConflict());
        assertThat(StubErp.CALLS).hasSize(1); // no reservation attempt for an order that cannot be released
    }

    @Test
    void searchFiltersAndPages() throws Exception {
        String line = "LINE-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            perform(post("/api/orders").content(order("BRACKET-" + i, 5, line)), PLANNER).andExpect(status().isCreated());
        }
        perform(get("/api/orders").param("line", line).param("status", "PLANNED").param("size", "2"), VIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalItems").value(3));
        perform(get("/api/orders").param("size", "500"), VIEWER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").value("must be less than or equal to 200"));
    }

    @Test
    void outboxRelayPublishesEachEventOnce() throws Exception {
        UUID id = createOrder();
        perform(post("/api/orders/" + id + "/cancel"), PLANNER).andExpect(status().isOk());
        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(relay.relayBatch()).isZero();
        assertThat(jdbc.sql("select count(*) from outbox_events where published_at is null").query(Long.class).single()).isZero();
    }

    @Test
    void exposesProbesAndBusinessMetrics() throws Exception {
        createOrder();
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        String metrics = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("orders_planned_total{", "orders_outbox_pending", "resilience4j_circuitbreaker_state{",
                "http_server_requests_seconds_bucket{");
    }
}
