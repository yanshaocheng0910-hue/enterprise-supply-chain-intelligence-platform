package com.scic.platform.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scic.platform.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_failure_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "scic.demo.synthetic-history-enabled=false"
})
@AutoConfigureMockMvc
class AiFailureIntegrationTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 31);
    private static final String REQUEST_ID = "ai-failure-acceptance";
    private static final String INTERNAL_TOKEN = "test-internal-token-never-echo";
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool();
    private static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    private static final AtomicReference<Map<String, String>> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private String buyerToken;

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry properties) {
        properties.add("scic.ai.base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        properties.add("scic.ai.service-token", () -> INTERNAL_TOKEN);
        properties.add("scic.ai.connect-timeout-ms", () -> 200);
        properties.add("scic.ai.read-timeout-ms", () -> 200);
    }

    @BeforeEach
    void prepare() throws Exception {
        REPLY.set(new Reply(500, "application/json", "{}", 0));
        LAST_REQUEST.set(Map.of());
        REQUEST_COUNT.set(0);
        if (jdbc.queryForObject("select count(*) from demand_history where material_id=5", Integer.class) == 0) {
            jdbc.update("insert into demand_history(material_id,demand_date,quantity,source_system,data_label) values(5,?,20,'FAULT_TEST','DEMO_SYNTHETIC')", AS_OF);
        }
        buyerToken = token("buyer");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
        WORKERS.shutdownNow();
    }

    static Stream<Arguments> failures() {
        return Stream.of("parse", "forecast").flatMap(endpoint -> Stream.of(
                Arguments.of(endpoint, 401, "application/json", "{\"detail\":\"secret-upstream-error\"}", 0, 502, "AI_SERVICE_AUTH_FAILED"),
                Arguments.of(endpoint, 403, "application/json", "{\"detail\":\"secret-upstream-error\"}", 0, 502, "AI_SERVICE_AUTH_FAILED"),
                Arguments.of(endpoint, 500, "application/json", "{\"detail\":\"secret-upstream-error\"}", 0, 502, "AI_PROVIDER_ERROR"),
                Arguments.of(endpoint, 422, "application/json", "{\"detail\":\"secret-upstream-error\"}", 0, 422, "AI_OUTPUT_INVALID"),
                Arguments.of(endpoint, 200, "application/json", "{broken", 0, 502, "AI_OUTPUT_INVALID"),
                Arguments.of(endpoint, 200, "text/html", "<html>secret-upstream-error</html>", 0, 502, "AI_OUTPUT_INVALID"),
                Arguments.of(endpoint, 200, "application/json", "", 0, 502, "AI_OUTPUT_INVALID"),
                Arguments.of(endpoint, 200, "application/json", "{}", 600, 503, "AI_SERVICE_UNAVAILABLE")
        ));
    }

    @ParameterizedTest(name = "{0}: upstream {1}, expected {5}/{6}")
    @MethodSource("failures")
    void upstreamFailuresAreControlledAndNeverWriteBusinessData(String endpoint, int upstreamStatus,
            String contentType, String body, int delayMs, int expectedStatus, String expectedCode) throws Exception {
        REPLY.set(new Reply(upstreamStatus, contentType, body, delayMs));
        Map<String, Long> before = businessCounts();
        String response = mvc.perform(post(apiPath(endpoint)).header("Authorization", "Bearer " + buyerToken)
                        .header("X-Request-ID", REQUEST_ID).contentType(MediaType.APPLICATION_JSON).content(requestBody(endpoint)))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(expectedCode))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).doesNotContain(INTERNAL_TOKEN, "secret-upstream-error", "{broken");
        assertThat(businessCounts()).isEqualTo(before);
        assertThat(REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(LAST_REQUEST.get()).containsEntry("path", "/internal/v1/" + endpoint)
                .containsEntry("token", INTERNAL_TOKEN).containsEntry("requestId", REQUEST_ID);
    }

    @Test
    void connectionRefusalIsUnavailableForBothOperations() throws Exception {
        try (ServerSocket reserved = new ServerSocket()) {
            reserved.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = reserved.getLocalPort();
            reserved.close();
            AiGateway gateway = new AiGateway("http://127.0.0.1:" + port, INTERNAL_TOKEN, 100, 100);
            for (String endpoint : List.of("parse", "forecast")) {
                assertThatThrownBy(() -> {
                    if (endpoint.equals("parse")) gateway.parse(Map.of(), REQUEST_ID);
                    else gateway.forecast(Map.of(), REQUEST_ID);
                }).isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.code()).isEqualTo("AI_SERVICE_UNAVAILABLE");
                    assertThat(ex.getMessage()).contains("手工处理").doesNotContain(INTERNAL_TOKEN);
                });
            }
        }
    }

    static Stream<String> invalidForecasts() {
        return Stream.of("missing-model", "unknown-model", "missing-sequence", "short-sequence", "non-object-point",
                "missing-date", "invalid-date", "duplicate-date", "gap-in-dates", "reversed-dates",
                "missing-quantity", "negative-quantity", "nonnumeric-quantity", "overflow-quantity",
                "invalid-metric", "overflow-metric");
    }

    @ParameterizedTest(name = "invalid forecast: {0}")
    @MethodSource("invalidForecasts")
    void invalidForecastNeverBecomesSuccessfulRunOrPurchaseSuggestion(String fault) throws Exception {
        Map<String, Object> response = forecastResponse();
        @SuppressWarnings("unchecked") List<Map<String, Object>> sequence = (List<Map<String, Object>>) response.get("sequence");
        switch (fault) {
            case "missing-model" -> response.remove("model");
            case "unknown-model" -> response.put("model", "not-a-forecast-model");
            case "missing-sequence" -> response.remove("sequence");
            case "short-sequence" -> sequence.remove(13);
            case "non-object-point" -> {
                List<Object> malformed = new ArrayList<>(sequence);
                malformed.set(13, "not-a-point");
                response.put("sequence", malformed);
            }
            case "missing-date" -> sequence.get(13).remove("date");
            case "invalid-date" -> sequence.get(13).put("date", "not-a-date");
            case "duplicate-date" -> sequence.get(13).put("date", AS_OF.plusDays(13).toString());
            case "gap-in-dates" -> sequence.get(13).put("date", AS_OF.plusDays(15).toString());
            case "reversed-dates" -> java.util.Collections.swap(sequence, 0, 1);
            case "missing-quantity" -> sequence.get(13).remove("forecast");
            case "negative-quantity" -> sequence.get(13).put("forecast", -1);
            case "nonnumeric-quantity" -> sequence.get(13).put("forecast", "NaN");
            case "overflow-quantity" -> sequence.get(13).put("forecast", new BigDecimal("100000000000000"));
            case "invalid-metric" -> response.put("metrics", Map.of("mae", "NaN", "rmse", 0, "mape", 0));
            case "overflow-metric" -> response.put("metrics", Map.of("mae", new BigDecimal("1000000000000"), "rmse", 0, "mape", 0));
            default -> throw new IllegalArgumentException(fault);
        }
        REPLY.set(new Reply(200, "application/json", json.writeValueAsString(response), 0));
        Map<String, Long> before = businessCounts();
        mvc.perform(post(apiPath("forecast")).header("Authorization", "Bearer " + buyerToken)
                        .header("X-Request-ID", REQUEST_ID).contentType(MediaType.APPLICATION_JSON).content(requestBody("forecast")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_OUTPUT_INVALID"));
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void explicitMa7FallbackPersistsEvidenceWithoutCreatingPurchaseDemand() throws Exception {
        Map<String, Object> response = forecastResponse();
        response.put("fallback_reason", "DATA_INSUFFICIENT: 使用 MA7");
        response.put("warnings", List.of("样本不足，使用 MA7"));
        REPLY.set(new Reply(200, "application/json", json.writeValueAsString(response), 0));
        Map<String, Long> before = businessCounts();
        String body = mvc.perform(post(apiPath("forecast")).header("Authorization", "Bearer " + buyerToken)
                        .header("X-Request-ID", REQUEST_ID).contentType(MediaType.APPLICATION_JSON).content(requestBody("forecast")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.model_name").value("MA7"))
                .andExpect(jsonPath("$.data.fallback_reason").value("DATA_INSUFFICIENT: 使用 MA7"))
                .andExpect(jsonPath("$.data.results.length()").value(14))
                .andExpect(jsonPath("$.data.results[0].warning_code").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.warnings[0]").value("样本不足，使用 MA7"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(body).path("data").path("id").asLong();
        assertThat(businessCounts().get("forecast_run")).isEqualTo(before.get("forecast_run") + 1);
        assertThat(businessCounts().get("forecast_result")).isEqualTo(before.get("forecast_result") + 14);
        assertThat(businessCounts().get("purchase_demand")).isEqualTo(before.get("purchase_demand"));
        assertThat(jdbc.queryForObject("select count(*) from purchase_demand where forecast_run_id=?", Long.class, id)).isZero();
    }

    @Test
    void manualDemandRemainsUsableAfterAiAuthenticationFailure() throws Exception {
        REPLY.set(new Reply(403, "application/json", "{}", 0));
        mvc.perform(post(apiPath("parse")).header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody("parse")))
                .andExpect(status().isBadGateway());
        long before = businessCounts().get("purchase_demand");
        mvc.perform(post("/api/v1/procurement/demands").header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "materialCode", "MAT-BOX-05", "quantity", 20,
                                "expectedDate", LocalDate.now().plusDays(10).toString(), "priority", "NORMAL", "notes", "AI故障后手工处理回归"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        assertThat(businessCounts().get("purchase_demand")).isEqualTo(before + 1);
        assertThat(REQUEST_COUNT.get()).isEqualTo(1);
    }

    private Map<String, Object> forecastResponse() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int day = 1; day <= 14; day++) points.add(new LinkedHashMap<>(Map.of("date", AS_OF.plusDays(day).toString(), "forecast", 10)));
        return new LinkedHashMap<>(Map.of("model", "ma7", "sequence", points, "metrics", Map.of("mae", 0, "rmse", 0, "mape", 0),
                "evaluation", Map.of("split", Map.of("train", 0.6, "validation", 0.2, "test", 0.2)), "warnings", List.of()));
    }

    private Map<String, Long> businessCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("forecast_run", "forecast_result", "purchase_demand", "purchase_order", "ai_parse_record", "inventory")) {
            counts.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
        }
        return counts;
    }

    private String token(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = json.readTree(body);
        return response.path("data").path("accessToken").asText();
    }

    private String requestBody(String endpoint) throws Exception {
        return json.writeValueAsString(endpoint.equals("forecast") ? Map.of("materialCode", "MAT-BOX-05")
                : Map.of("taskType", "PURCHASE_DEMAND", "text", "请采购 MAT-BOX-05 20 件，交期明天"));
    }

    private static String apiPath(String endpoint) {
        return "/api/v1/intelligence/" + (endpoint.equals("forecast") ? "forecast-runs" : "parse-previews");
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/internal/v1/", exchange -> {
                Reply reply = REPLY.get();
                REQUEST_COUNT.incrementAndGet();
                LAST_REQUEST.set(Map.of("path", exchange.getRequestURI().getPath(),
                        "token", exchange.getRequestHeaders().getFirst("X-Service-Token"),
                        "requestId", exchange.getRequestHeaders().getFirst("X-Request-ID")));
                try {
                    exchange.getRequestBody().readAllBytes();
                    if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
                    exchange.getResponseHeaders().set("Content-Type", reply.contentType());
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Read-timeout cases deliberately close the socket before this response.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private record Reply(int status, String contentType, String body, int delayMs) {}
}
