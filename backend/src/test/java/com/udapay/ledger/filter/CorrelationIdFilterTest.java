package com.udapay.ledger.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercise 4 — W3C traceparent parsing and MDC lifecycle. */
class CorrelationIdFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("valid traceparent → MDC carries the incoming traceId/spanId during the request")
    void parsesTraceparentIntoMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transfers");
        request.addHeader("traceparent", TRACEPARENT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            seenTrace.set(MDC.get(CorrelationIdFilter.TRACE_ID));
            seenSpan.set(MDC.get(CorrelationIdFilter.SPAN_ID));
        };

        filter.doFilter(request, response, chain);

        assertThat(seenTrace.get()).isEqualTo(TRACE_ID);
        assertThat(seenSpan.get()).isEqualTo(SPAN_ID);
        assertThat(response.getHeader("traceparent")).isEqualTo(TRACEPARENT);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("MDC keys are cleared after the request (finally block)")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transfers");
        request.addHeader("traceparent", TRACEPARENT);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(MDC.get(CorrelationIdFilter.TRACE_ID)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.SPAN_ID)).isNull();
    }

    @Test
    @DisplayName("MDC keys are cleared even when downstream throws")
    void clearsMdcWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        request.addHeader("traceparent", TRACEPARENT);
        FilterChain failing = (req, res) -> { throw new ServletException("downstream failure"); };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdFilter.TRACE_ID)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.SPAN_ID)).isNull();
    }

    @Test
    @DisplayName("missing header → UUID-derived ids are generated and echoed back")
    void generatesIdsWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transfers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> {
            seenTrace.set(MDC.get(CorrelationIdFilter.TRACE_ID));
            seenSpan.set(MDC.get(CorrelationIdFilter.SPAN_ID));
        });

        assertThat(seenTrace.get()).matches("[0-9a-f]{32}");
        assertThat(seenSpan.get()).matches("[0-9a-f]{16}");
        assertThat(response.getHeader("traceparent"))
                .isEqualTo("00-" + seenTrace.get() + "-" + seenSpan.get() + "-01");
    }

    @Test
    @DisplayName("malformed or all-zero traceparent is ignored and fresh ids are generated")
    void ignoresMalformedHeader() throws Exception {
        for (String bad : new String[]{
                "garbage",
                "00-abc-def-01",
                "00-" + "0".repeat(32) + "-" + SPAN_ID + "-01",     // invalid trace id
                "00-" + TRACE_ID + "-" + "0".repeat(16) + "-01",    // invalid span id
                "ff-" + TRACE_ID + "-" + SPAN_ID + "-01",           // forbidden version
                "00-" + TRACE_ID.toUpperCase().substring(0, 31) + "G-" + SPAN_ID + "-01"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            request.addHeader("traceparent", bad);
            AtomicReference<String> seen = new AtomicReference<>();
            filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> seen.set(MDC.get(CorrelationIdFilter.TRACE_ID)));
            assertThat(seen.get()).as("header %s", bad).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
        }
    }

    @Test
    @DisplayName("generated ids are unique per request")
    void generatedIdsAreUnique() throws IOException, ServletException {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> first.set(MDC.get(CorrelationIdFilter.TRACE_ID)));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> second.set(MDC.get(CorrelationIdFilter.TRACE_ID)));
        assertThat(first.get()).isNotEqualTo(second.get());
    }
}
