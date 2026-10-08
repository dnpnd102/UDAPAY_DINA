package com.udapay.ledger.controller;

import com.udapay.ledger.model.Transfer;
import com.udapay.ledger.repository.TransferRepository;
import com.udapay.ledger.support.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of Exercises 1, 3 and 4 through the real filter chain:
 * Flyway + H2, Spring Security 7 resource server with RSA-signed test JWTs,
 * method security, security headers, the correlation-id filter and the ECS
 * JSON log output.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtSupport.class)
@ExtendWith(OutputCaptureExtension.class)
class LedgerApiIntegrationTest {

    private static final String INJECTION = "admin' OR '1'='1";
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransferRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        Transfer t1 = new Transfer("admin", new BigDecimal("10.00"), "USD", "seed-1");
        t1.setTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        Transfer t2 = new Transfer("alice", new BigDecimal("25.00"), "USD", "seed-2");
        t2.setTimestamp(Instant.parse("2026-01-02T00:00:00Z"));
        repository.save(t1);
        repository.save(t2);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ------------------------------------------------------------------ public

    @Nested
    @DisplayName("public endpoints")
    class PublicEndpoints {

        @Test
        void healthIsPublic() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        void prometheusIsPublic() throws Exception {
            mvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("jvm_")));
        }

        @Test
        void otherActuatorEndpointsRequireAuthentication() throws Exception {
            mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/env").header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------ authentication

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        void missingTokenIs401() throws Exception {
            mvc.perform(get("/api/v1/transfers"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"));
            mvc.perform(get("/api/v1/transfers/search").param("username", "admin"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void validTokenIs200() throws Exception {
            mvc.perform(get("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].username").value("alice"));   // newest first
        }

        @Test
        void tokenSignedByUnknownKeyIs401() throws Exception {
            mvc.perform(get("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.tokenSignedByRogueKey("bob", "reader"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void expiredTokenIs401() throws Exception {
            mvc.perform(get("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.expiredToken("bob", "reader"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void wrongIssuerIs401() throws Exception {
            mvc.perform(get("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.tokenFromWrongIssuer("bob", "reader"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void garbageTokenIs401() throws Exception {
            mvc.perform(get("/api/v1/transfers").header("Authorization", "Bearer not.a.jwt"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------- authorization

    @Nested
    @DisplayName("role-based authorization")
    class Authorization {

        @Test
        void readerCannotCreateTransfer() throws Exception {
            mvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 100.50}"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403));
            assertThat(repository.count()).isEqualTo(2);
        }

        @Test
        void writerCanCreateTransfer() throws Exception {
            mvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("alice", "writer")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 100.50, \"currency\": \"gbp\", \"description\": \"rent\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", containsString("/api/v1/transfers/")))
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.username").value("alice"))       // from preferred_username, not the body
                    .andExpect(jsonPath("$.amount").value(100.50))
                    .andExpect(jsonPath("$.currency").value("GBP"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
            assertThat(repository.count()).isEqualTo(3);
        }

        @Test
        void writerWithoutReaderCannotSearch() throws Exception {
            mvc.perform(get("/api/v1/transfers/search").param("username", "admin")
                            .header("Authorization", bearer(TestJwtSupport.token("alice", "writer"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void readerCanSearch() throws Exception {
            mvc.perform(get("/api/v1/transfers/search").param("username", "admin")
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].username").value("admin"));
        }

        @Test
        void tokenWithNoRolesCanListButNotSearchOrCreate() throws Exception {
            String token = TestJwtSupport.token("nobody");
            mvc.perform(get("/api/v1/transfers").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/transfers/search").param("username", "admin")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/transfers").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------ SQL injection

    @Nested
    @DisplayName("Exercise 1 — SQL injection via the search endpoint")
    class SqlInjection {

        @Test
        void injectionPayloadReturnsNoRows() throws Exception {
            mvc.perform(get("/api/v1/transfers/search").param("username", INJECTION)
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }

        @Test
        void injectionPayloadOnlyMatchesALiteralUsername() throws Exception {
            repository.save(new Transfer(INJECTION, new BigDecimal("1.00"), "USD", "literal"));

            mvc.perform(get("/api/v1/transfers/search").param("username", INJECTION)
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].username").value(INJECTION));
        }
    }

    // ---------------------------------------------------------- input validation

    @Nested
    @DisplayName("input validation (RFC 7807)")
    class Validation {

        @Test
        void negativeAmountIsRejected() throws Exception {
            mvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("alice", "writer")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": -5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"));
        }

        @Test
        void missingAmountIsRejected() throws Exception {
            mvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("alice", "writer")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\": \"no amount\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void malformedJsonIsRejected() throws Exception {
            mvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("alice", "writer")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Malformed request body"));
        }

        @Test
        void blankSearchUsernameIsRejected() throws Exception {
            mvc.perform(get("/api/v1/transfers/search").param("username", "   ")
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void overlongSearchUsernameIsRejected() throws Exception {
            mvc.perform(get("/api/v1/transfers/search").param("username", "a".repeat(101))
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------------------------------------------------------- security headers

    @Nested
    @DisplayName("defence-in-depth headers / CORS")
    class Headers {

        @Test
        void securityHeadersArePresent() throws Exception {
            mvc.perform(get("/api/v1/transfers")
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Strict-Transport-Security",
                            containsString("max-age=31536000")))
                    .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }

        @Test
        void securityHeadersAlsoOn401() throws Exception {
            mvc.perform(get("/api/v1/transfers"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("Content-Security-Policy"))
                    .andExpect(header().exists("Strict-Transport-Security"));
        }

        @Test
        void corsPreflightFromPort5500IsAllowed() throws Exception {
            mvc.perform(options("/api/v1/transfers")
                            .header("Origin", "http://localhost:5500")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
        }

        @Test
        void corsFromOtherOriginIsRejected() throws Exception {
            mvc.perform(options("/api/v1/transfers")
                            .header("Origin", "http://evil.example")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------- trace context / logs

    @Nested
    @DisplayName("Exercise 4 — W3C trace context and ECS JSON logs")
    class Tracing {

        @Test
        void incomingTraceparentIsEchoedAndLoggedAsJson(CapturedOutput output) throws Exception {
            mvc.perform(get("/api/v1/transfers/search").param("username", "admin")
                            .header("traceparent", TRACEPARENT)
                            .header("Authorization", bearer(TestJwtSupport.token("bob", "reader"))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("traceparent", TRACEPARENT))
                    .andExpect(header().string("X-Correlation-Id", TRACE_ID));

            String logs = output.getOut();
            assertThat(logs).contains("\"traceId\":\"" + TRACE_ID + "\"");
            assertThat(logs).contains("\"spanId\":\"" + SPAN_ID + "\"");

            // The service log line is a JSON object that carries the trace context and ECS fields
            String line = logs.lines()
                    .filter(l -> l.contains("Searching transfers for username='admin'"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected service log line not found in:\n" + logs));
            assertThat(line).startsWith("{").endsWith("}");
            assertThat(line).contains("\"@timestamp\":")
                    .contains("\"log.level\":\"INFO\"")
                    .contains("\"log.logger\":\"com.udapay.ledger.service.TransferService\"")
                    .contains("\"service.name\":\"udapay-ledger\"")
                    .contains("\"traceId\":\"" + TRACE_ID + "\"")
                    .contains("\"spanId\":\"" + SPAN_ID + "\"");
        }

        @Test
        void missingTraceparentGetsGeneratedIds() throws Exception {
            MvcResult result = mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("traceparent", matchesPattern("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
                    .andReturn();
            String traceId = result.getResponse().getHeader("X-Correlation-Id");
            assertThat(traceId).matches("[0-9a-f]{32}");
        }

        @Test
        void problemDetailsCarryTheTraceId() throws Exception {
            mvc.perform(post("/api/v1/transfers")
                            .header("traceparent", TRACEPARENT)
                            .header("Authorization", bearer(TestJwtSupport.token("alice", "writer")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID));
        }
    }
}
