package com.udapay.ledger.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Propagates W3C Trace Context (<a href="https://www.w3.org/TR/trace-context/">traceparent</a>)
 * into SLF4J's MDC so every JSON log line carries {@code traceId} and {@code spanId}.
 *
 * <pre>
 * traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
 *              │  └── traceId (32 hex)                └── spanId (16 hex)  └── flags
 *              └── version
 * </pre>
 *
 * If the header is missing or malformed, fresh identifiers are generated from
 * {@link UUID#randomUUID()}. The resolved context is echoed back to the caller
 * in the {@code traceparent} response header so clients can correlate too.
 * Both MDC keys are always removed in {@code finally} — request threads are
 * pooled and must never leak context into the next request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TraceContext ctx = parse(request.getHeader(TRACEPARENT_HEADER));

        MDC.put(TRACE_ID, ctx.traceId());
        MDC.put(SPAN_ID, ctx.spanId());

        response.setHeader(TRACEPARENT_HEADER, ctx.toTraceparent());
        response.setHeader(CORRELATION_ID_HEADER, ctx.traceId());

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(SPAN_ID);
        }
    }

    /**
     * Parses a {@code traceparent} value. Returns generated identifiers when the
     * header is absent, malformed, or uses the all-zero (invalid) ids.
     */
    static TraceContext parse(String traceparent) {
        if (traceparent != null) {
            Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.trim().toLowerCase());
            if (m.matches()) {
                String version = m.group(1);
                String traceId = m.group(2);
                String spanId = m.group(3);
                boolean validVersion = !"ff".equals(version);
                boolean validIds = !INVALID_TRACE_ID.equals(traceId) && !INVALID_SPAN_ID.equals(spanId);
                if (validVersion && validIds) {
                    return new TraceContext(traceId, spanId);
                }
            }
        }
        return new TraceContext(newTraceId(), newSpanId());
    }

    /** 32 lowercase hex chars derived from a random UUID (128 bits). */
    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 16 lowercase hex chars derived from a random UUID (64 bits). */
    static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Resolved trace context for the current request. */
    record TraceContext(String traceId, String spanId) {
        String toTraceparent() {
            return "00-" + traceId + "-" + spanId + "-01";
        }
    }
}
